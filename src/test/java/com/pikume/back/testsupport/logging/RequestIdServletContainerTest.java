package com.pikume.back.testsupport.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.global.logging.CapturingLogAppender;
import com.pikume.back.global.logging.RequestIdConfiguration;
import com.pikume.back.global.logging.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RequestIdServletContainerTest.ContainerConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=request-id-container-test", "server.address=127.0.0.1",
                "server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"})
@ActiveProfiles("prod")
class RequestIdServletContainerTest {
    private static final String SUPPLIED_ID = "1234567890abcdef1234567890abcdef";
    private static final String NEXT_ID = "abcdef1234567890abcdef1234567890";

    @LocalServerPort
    private int port;
    @Autowired
    private ProbeState state;
    @Autowired
    private ThreadPoolTaskExecutor callableExecutor;
    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetProbe() {
        state.dispatches.clear();
        state.worker = null;
        state.interrupted = new CountDownLatch(1);
        state.release = new CountDownLatch(1);
    }

    @AfterEach
    void releaseWorker() throws Exception {
        state.release.countDown();
        callableExecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @CsvSource({"/probe/failure,500,false", "/probe/failure,500,true",
            "/probe/callable-failure,500,false", "/probe/callable-failure,500,true",
            "/probe/timeout,503,false", "/probe/timeout,503,true"})
    void errorAndTimeoutKeepOneIdAndLeaveReusedThreadsClean(String path, int status, boolean supplied) throws Exception {
        try (var generatedLogs = new CapturingLogAppender(RequestIdFilter.class);
             var requestLogs = new CapturingLogAppender(ProbeController.class)) {
            var response = get(path, supplied ? SUPPLIED_ID : null);
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();

            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.headers().allValues("X-Request-Id")).containsExactly(requestId);
            assertThat(requestId).matches("[0-9a-f]{32}");
            if (supplied) {
                assertThat(requestId).isEqualTo(SUPPLIED_ID);
            }
            var problem = objectMapper.readTree(response.body());
            assertThat(problem.path("status").asInt()).isEqualTo(status);
            assertThat(problem.path("detail").asText()).isNotBlank();
            assertThat(problem.path("requestId").asText()).isEqualTo(requestId);
            assertThat(requestLogs.events()).isNotEmpty().allSatisfy(event ->
                    assertThat(event.context()).containsEntry("requestId", requestId));
            assertThat(requestLogs.events()).extracting(CapturingLogAppender.Event::message)
                    .contains("event=request_id_probe dispatch=REQUEST", "event=request_id_probe dispatch=ERROR");
            if (!path.equals("/probe/failure")) {
                assertThat(requestLogs.events()).extracting(CapturingLogAppender.Event::message)
                        .contains("event=request_id_probe dispatch=ASYNC");
            }
            if (supplied) {
                assertThat(generatedLogs.events()).isEmpty();
            } else {
                assertThat(generatedLogs.events()).singleElement().satisfies(event -> {
                    assertThat(event.message()).isEqualTo("event=request_id_generated reason=missing_header");
                    assertThat(event.context()).containsEntry("requestId", requestId);
                });
            }

            if (!path.equals("/probe/failure")) {
                if (path.equals("/probe/timeout")) {
                    assertThat(state.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                    state.release.countDown();
                }
                WorkerObservation reusedWorker = callableExecutor.submit(WorkerObservation::capture)
                        .get(5, TimeUnit.SECONDS);
                assertThat(state.worker.requestId()).isEqualTo(requestId);
                assertThat(reusedWorker.threadId()).isEqualTo(state.worker.threadId());
                assertThat(reusedWorker.requestId()).isNull();
            }

            var next = get("/probe/context", NEXT_ID);
            assertThat(next.statusCode()).isEqualTo(200);
            assertThat(next.body()).isEqualTo(NEXT_ID);
            assertThat(next.headers().allValues("X-Request-Id")).containsExactly(NEXT_ID);
            int dispatchCount = path.equals("/probe/failure") ? 3 : 4;
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(state.dispatches).hasSize(dispatchCount));
            assertThat(state.dispatches).extracting(DispatchObservation::type)
                    .contains(DispatcherType.REQUEST, DispatcherType.ERROR);
            if (!path.equals("/probe/failure")) {
                assertThat(state.dispatches).extracting(DispatchObservation::type).contains(DispatcherType.ASYNC);
            }
            assertThat(state.dispatches).allSatisfy(dispatch -> {
                assertThat(dispatch.before()).isNull();
                assertThat(dispatch.after()).isNull();
            });
            assertThat(state.dispatches.stream().map(DispatchObservation::threadId).distinct()).hasSize(1);
        }
    }

    private HttpResponse<String> get(String path, String requestId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    record DispatchObservation(DispatcherType type, String before, String after, long threadId) { }

    record WorkerObservation(String requestId, long threadId) {
        static WorkerObservation capture() {
            return new WorkerObservation(MDC.get("requestId"), Thread.currentThread().getId());
        }
    }

    static class ProbeState {
        final List<DispatchObservation> dispatches = new CopyOnWriteArrayList<>();
        volatile WorkerObservation worker;
        CountDownLatch interrupted;
        CountDownLatch release;
    }

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class})
    @Import(RequestIdConfiguration.class)
    static class ContainerConfiguration {
        @Bean
        ProbeState probeState() {
            return new ProbeState();
        }

        @Bean
        ProbeController probeController(ProbeState state) {
            return new ProbeController(state);
        }

        @Bean
        ThreadPoolTaskExecutor callableExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("request-id-test-");
            return executor;
        }

        @Bean
        WebMvcConfigurer asyncConfiguration(ThreadPoolTaskExecutor callableExecutor) {
            return new WebMvcConfigurer() {
                @Override
                public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                    configurer.setTaskExecutor(callableExecutor);
                }
            };
        }

        @Bean
        FilterRegistrationBean<Filter> activeContextObserver() {
            Filter observer = (request, response, chain) -> {
                LoggerFactory.getLogger(ProbeController.class).info("event=request_id_probe dispatch={}",
                        request.getDispatcherType());
                chain.doFilter(request, response);
            };
            return observerRegistration(observer, Ordered.HIGHEST_PRECEDENCE + 11);
        }

        @Bean
        FilterRegistrationBean<Filter> contextObserver(ProbeState state) {
            Filter observer = (request, response, chain) -> {
                DispatcherType type = request.getDispatcherType();
                String before = MDC.get("requestId");
                try {
                    chain.doFilter(request, response);
                } finally {
                    state.dispatches.add(new DispatchObservation(type, before, MDC.get("requestId"),
                            Thread.currentThread().getId()));
                }
            };
            return observerRegistration(observer, Ordered.HIGHEST_PRECEDENCE + 9);
        }

        private FilterRegistrationBean<Filter> observerRegistration(Filter observer, int order) {
            var registration = new FilterRegistrationBean<>(observer);
            registration.setOrder(order);
            registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
            registration.setAsyncSupported(true);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    @TestComponent
    @RestController
    static class ProbeController implements ErrorController {
        private final ProbeState state;

        ProbeController(ProbeState state) {
            this.state = state;
        }

        @GetMapping("/probe/failure")
        String failure() {
            LoggerFactory.getLogger(ProbeController.class).info("event=request_id_probe stage=request");
            throw new IllegalStateException("container error test");
        }

        @GetMapping("/probe/callable-failure")
        Callable<String> callableFailure() {
            return () -> {
                state.worker = WorkerObservation.capture();
                LoggerFactory.getLogger(ProbeController.class).info("event=request_id_probe stage=callable");
                throw new IllegalStateException("callable error test");
            };
        }

        @GetMapping("/probe/timeout")
        WebAsyncTask<String> timeout() {
            return new WebAsyncTask<>(100L, () -> {
                state.worker = WorkerObservation.capture();
                LoggerFactory.getLogger(ProbeController.class).info("event=request_id_probe stage=callable");
                try {
                    state.release.await(10, TimeUnit.SECONDS);
                    return "unexpected completion";
                } catch (InterruptedException exception) {
                    state.interrupted.countDown();
                    // Let the timeout response finish before the cancelled task returns.
                    state.release.await(5, TimeUnit.SECONDS);
                    return "late result";
                }
            });
        }

        @RequestMapping("/error")
        ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
            LoggerFactory.getLogger(ProbeController.class).info("event=request_id_probe stage=error");
            int status = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "Request failed in container test");
            problem.setProperty("requestId", MDC.get("requestId"));
            return ResponseEntity.status(status).body(problem);
        }

        @GetMapping("/probe/context")
        String context() {
            return MDC.get("requestId");
        }
    }
}
