package com.pikume.back.global.logging;

import ch.qos.logback.classic.Level;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {
    private static final String VALID_ID = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {VALID_ID, "ABCDEF0123456789ABCDEF0123456789"})
    void validHeaderIsPreservedAndResponseHeaderIsReplaced(String requestId) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", requestId);
        var response = new MockHttpServletResponse();
        response.addHeader("X-Request-Id", "old-value");
        response.addHeader("X-Request-Id", "another-value");
        MDC.put("existing", "outer");

        try (var logs = new CapturingLogAppender(RequestIdFilter.class)) {
            filter("prod").doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("requestId")).isEqualTo(requestId);
                assertThat(MDC.get("existing")).isEqualTo("outer");
            });

            assertThat(response.getHeaders("X-Request-Id")).containsExactly(requestId);
            assertThat(logs.events()).isEmpty();
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("existing", "outer"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-an-id", "0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0", "0123456789abcdef0123456789abcdef ",
            "gggggggggggggggggggggggggggggggg", "\r\nevent=forged", "é123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef,0123456789abcdef0123456789abcdef"})
    void invalidHeaderIsReplacedWithoutLoggingItsValue(String invalid) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", invalid);
        var response = new MockHttpServletResponse();

        try (var logs = new CapturingLogAppender(RequestIdFilter.class)) {
            filter("prod").doFilter(request, response, (req, res) ->
                    assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id")));

            assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}").isNotEqualTo(invalid);
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.message()).isEqualTo("event=request_id_generated reason=invalid_header");
                assertThat(event.level()).isEqualTo(Level.WARN);
                assertThat(event.context()).containsOnlyKeys("requestId");
            });
            assertThat(MDC.get("requestId")).isNull();
        }
    }

    @Test
    void repeatedHeaderIsRejectedEvenWhenBothValuesAreValid() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", List.of(VALID_ID, VALID_ID));
        var response = new MockHttpServletResponse();

        try (var logs = new CapturingLogAppender(RequestIdFilter.class)) {
            filter("prod").doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}").isNotEqualTo(VALID_ID);
            assertThat(logs.events()).singleElement().satisfies(event ->
                    assertThat(event.message()).endsWith("reason=invalid_header"));
        }
    }

    @ParameterizedTest
    @CsvSource({"prod,/api/diary,WARN", "dev,/api/diary,DEBUG", "test,/api/diary,DEBUG",
            "default,/api/diary,DEBUG", "prod,/actuator,DEBUG", "prod,/actuator/health,DEBUG",
            "prod,/actuator-api,WARN"})
    void generatedIdUsesEnvironmentAndManagementSpecificLevel(String profile, String path, String level) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        var response = new MockHttpServletResponse();

        try (var logs = new CapturingLogAppender(RequestIdFilter.class)) {
            filter(profile).doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}");
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.level()).isEqualTo(Level.valueOf(level));
                assertThat(event.message()).isEqualTo("event=request_id_generated reason=missing_header");
                assertThat(event.context()).containsEntry("requestId", response.getHeader("X-Request-Id"));
            });
        }
    }

    @Test
    void managementBasePathAndServletContextAreRespected() throws Exception {
        var environment = new MockEnvironment().withProperty("management.endpoints.web.base-path", "/manage/");
        environment.setActiveProfiles("prod");
        var filter = new RequestIdFilter(environment);
        var request = new MockHttpServletRequest("GET", "/backend/manage/health");
        request.setContextPath("/backend");

        try (var logs = new CapturingLogAppender(RequestIdFilter.class)) {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

            assertThat(logs.events()).singleElement().satisfies(event -> assertThat(event.level()).isEqualTo(Level.DEBUG));
        }
    }

    @Test
    void asyncAndErrorDispatchesReuseTheInitialIdAndDoNotRepeatGenerationLog() throws Exception {
        var filter = filter("prod");
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        try (var logs = new CapturingLogAppender(RequestIdFilter.class)) {
            filter.doFilter(request, response, (req, res) -> { });
            String requestId = response.getHeader("X-Request-Id");
            request.addHeader("X-Request-Id", VALID_ID);
            for (var dispatch : List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
                request.setDispatcherType(dispatch);
                filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("requestId")).isEqualTo(requestId));
                assertThat(response.getHeaders("X-Request-Id")).containsExactly(requestId);
                assertThat(MDC.get("requestId")).isNull();
            }
            assertThat(logs.events()).hasSize(1);
        }
    }

    @Test
    void nestedErrorDispatchAndThrownFailureRestoreEachPreviousContext() {
        var filter = filter("prod");
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "outer-context");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            String initial = MDC.get("requestId");
            request.setDispatcherType(DispatcherType.ERROR);
            filter.doFilter(request, response, (nestedRequest, nestedResponse) ->
                    assertThat(MDC.get("requestId")).isEqualTo(initial));
            assertThat(MDC.get("requestId")).isEqualTo(initial);
            throw new IOException("transport failure");
        })).isInstanceOf(IOException.class);
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}");
    }

    @Test
    void consecutiveRequestsGetSeparateIdsAndLeaveNoRequestContext() throws Exception {
        var filter = filter("dev");
        var first = new MockHttpServletResponse();
        var second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> { });
        assertThat(MDC.get("requestId")).isNull();
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> { });

        assertThat(second.getHeader("X-Request-Id")).isNotEqualTo(first.getHeader("X-Request-Id"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void concurrentRequestsHaveSeparateContextOnReusedWorkers() throws Exception {
        var filter = filter("dev");
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var tasks = List.of(VALID_ID, "abcdef0123456789abcdef0123456789").stream()
                    .map(id -> executor.submit(() -> {
                        var request = new MockHttpServletRequest();
                        request.addHeader("X-Request-Id", id);
                        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                            try {
                                barrier.await(5, TimeUnit.SECONDS);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                            assertThat(MDC.get("requestId")).isEqualTo(id);
                        });
                        return MDC.get("requestId");
                    })).toList();

            for (var result : tasks) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private RequestIdFilter filter(String profile) {
        var environment = new MockEnvironment();
        if (!profile.equals("default")) {
            environment.setActiveProfiles(profile);
        }
        return new RequestIdFilter(environment);
    }
}
