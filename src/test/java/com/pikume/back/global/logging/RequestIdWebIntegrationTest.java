package com.pikume.back.global.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {RequestIdWebIntegrationTest.WebConfiguration.class,
        RequestIdWebIntegrationTest.TestController.class})
class RequestIdWebIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ThreadPoolTaskExecutor testExecutor;

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void requestWithoutNginxSucceedsAndReturnsItsLogContext() throws Exception {
        var response = mvc.perform(get("/context"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}");
        assertThat(response.getContentAsString()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(response.getHeaders("X-Request-Id")).hasSize(1);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void securityRejectionAlreadyHasRequestContext() throws Exception {
        String requestId = "1234567890abcdef1234567890abcdef";
        var response = mvc.perform(get("/protected").header("X-Request-Id", requestId))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestId);
        assertThat(response.getHeader("Security-Request-Id")).isEqualTo(requestId);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void callableAndAsyncRedispatchKeepTheInitialRequestId() throws Exception {
        String requestId = "abcdef1234567890abcdef1234567890";
        var initial = mvc.perform(get("/callable").header("X-Request-Id", requestId))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(MDC.get("requestId")).isNull();

        mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                .andExpect(content().string(requestId))
                .andExpect(header().string("X-Request-Id", requestId));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(testExecutor.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS)).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void consoleAndFileEncodersIncludeRequestIdAndHaveNoStaleValueOutsideRequests() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdWebIntegrationTest.class);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        MDC.put("requestId", "1234567890abcdef1234567890abcdef");
        var requestEvent = new LoggingEvent(getClass().getName(), logger, Level.INFO, "event=request_test", null, null);
        requestEvent.prepareForDeferredProcessing();
        MDC.clear();
        var outsideEvent = new LoggingEvent(getClass().getName(), logger, Level.INFO, "event=background_test", null, null);
        outsideEvent.prepareForDeferredProcessing();

        for (String name : List.of("STDOUT", "FILE")) {
            var appender = (OutputStreamAppender<ILoggingEvent>) root.getAppender(name);
            assertThat(appender).isNotNull();
            String requestLog = new String(appender.getEncoder().encode(requestEvent), StandardCharsets.UTF_8);
            String outsideLog = new String(appender.getEncoder().encode(outsideEvent), StandardCharsets.UTF_8);
            assertThat(requestLog).contains("requestId=1234567890abcdef1234567890abcdef", "event=request_test");
            assertThat(outsideLog).contains("requestId=none", "event=background_test")
                    .doesNotContain("1234567890abcdef1234567890abcdef");
        }
    }

    @Configuration
    @ComponentScan("com.pikume.back.global.logging")
    static class WebConfiguration {
        @Bean
        ThreadPoolTaskExecutor testExecutor() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            return executor;
        }

        @Bean
        WebMvcConfigurer asyncConfiguration(ThreadPoolTaskExecutor testExecutor) {
            return new WebMvcConfigurer() {
                @Override
                public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                    configurer.setTaskExecutor(testExecutor);
                }
            };
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/protected").authenticated()
                            .anyRequest().permitAll())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> {
                        response.setHeader("Security-Request-Id", MDC.get("requestId"));
                        LoggerFactory.getLogger(RequestIdWebIntegrationTest.class)
                                .warn("event=authentication_failed outcome=denied");
                        response.sendError(401);
                    })).build();
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/context")
        String context() {
            return String.valueOf(MDC.get("requestId"));
        }

        @GetMapping("/callable")
        Callable<String> callable() {
            return () -> String.valueOf(MDC.get("requestId"));
        }
    }
}
