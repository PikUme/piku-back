package com.pikume.back.global.notification;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.global.logging.CapturingLogAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DiscordWebhookServiceTest {
    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890abcdef1234567890abcdef"})
    @NullSource
    void notificationBodyUsesResolvedContextInsteadOfRawHeader(String requestId) throws Exception {
        var serializedBody = new AtomicReference<String>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(outgoing -> {
            var transport = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.test/webhook"));
            return outgoing.writeTo(transport, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(transport::getBodyAsString))
                    .doOnNext(serializedBody::set)
                    .thenReturn(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        });
        var service = new DiscordWebhookService(builder);
        ReflectionTestUtils.setField(service, "webhookUrl", "https://example.test/webhook");
        if (requestId != null) {
            MDC.put("requestId", requestId);
        }
        var request = new MockHttpServletRequest("GET", "/api/sample");
        request.addHeader("X-Request-Id", "untrusted-header");

        service.sendExceptionNotification(new IllegalStateException("private exception"), request);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(serializedBody.get()).isNotNull());
        var body = new ObjectMapper().readTree(serializedBody.get());
        var fields = body.path("embeds").get(0).path("fields");
        assertThat(fields.findValuesAsText("name")).containsOnlyOnce("Request-Id");
        assertThat(fields).anySatisfy(field -> {
            assertThat(field.path("name").asText()).isEqualTo("Request-Id");
            assertThat(field.path("value").asText()).isEqualTo(requestId == null ? "none" : requestId);
        });
        assertThat(serializedBody.get()).doesNotContain("untrusted-header", "private exception");
        assertThat(MDC.get("requestId")).isEqualTo(requestId);
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 502})
    void asynchronousResponseLogsUseOriginalContextAndRestoreWorker(int status) throws Exception {
        Sinks.One<ClientResponse> response = Sinks.one();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> response.asMono());
        DiscordWebhookService service = new DiscordWebhookService(builder);
        ReflectionTestUtils.setField(service, "webhookUrl", "https://example.test/webhook");
        MDC.put("requestId", "11111111111111111111111111111111");
        var executor = Executors.newSingleThreadExecutor();

        try (var logs = new CapturingLogAppender(DiscordWebhookService.class)) {
            service.sendExceptionNotification(new IllegalStateException("private exception"),
                    new MockHttpServletRequest("GET", "/api/sample"));
            MDC.clear();
            String restored = executor.submit(() -> {
                MDC.put("requestId", "22222222222222222222222222222222");
                response.emitValue(ClientResponse.create(HttpStatus.valueOf(status)).build(),
                        Sinks.EmitFailureHandler.FAIL_FAST);
                return MDC.get("requestId");
            }).get(5, TimeUnit.SECONDS);

            assertThat(restored).isEqualTo("22222222222222222222222222222222");
            assertThat(logs.events()).hasSize(2).allSatisfy(event ->
                    assertThat(event.context()).containsEntry("requestId", "11111111111111111111111111111111"));
            assertThat(logs.events().get(1).level()).isEqualTo(status == 204 ? Level.DEBUG : Level.ERROR);
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.message())
                    .doesNotContain("private exception", "https://example.test/webhook"));
        } finally {
            executor.shutdownNow();
        }
    }
}
