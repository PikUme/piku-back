package com.pikume.back.global.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pikume.back.global.notification.dto.OperationalAlert;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordWebhookService")
class DiscordWebhookServiceTest {

    private static final String WEBHOOK_URL = "https://discord.example/webhook/test-secret";

    @Test
    @DisplayName("운영 웹훅이 비어 있거나 기본 placeholder면 시작 설정 검증에 실패한다")
    void rejectsMissingWebhookConfiguration() {
        DiscordWebhookService missingUrl = new DiscordWebhookService(
                " ", mock(WebClient.Builder.class), new SimpleMeterRegistry(), Duration.ofSeconds(1));
        DiscordWebhookService placeholderUrl = new DiscordWebhookService(
                "discord_webhook_url", mock(WebClient.Builder.class), new SimpleMeterRegistry(), Duration.ofSeconds(1));

        assertThat(catchThrowable(missingUrl::validateWebhookConfiguration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("discord.webhook.url must be configured for prod profile");
        assertThat(catchThrowable(placeholderUrl::validateWebhookConfiguration))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Redis 장애 알림은 중립 필드만 Discord payload로 전송한다")
    void sendsOperationalAlertWithoutRequestOrExceptionObjects() {
        AtomicReference<String> serializedBody = new AtomicReference<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DiscordWebhookService service = service(WEBHOOK_URL, request -> {
            serializedBody.set(serializeBody(request));
            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        }, Duration.ofSeconds(1), registry);

        service.sendOperationalAlert(alert());

        assertThat(serializedBody.get())
                .contains("Redis 장애")
                .contains("production")
                .contains("2026-09-30T12:34:56")
                .contains("/api/auth/signup")
                .contains("POST")
                .contains("가입 토큰 확인")
                .contains("RedisConnectionFailureException")
                .contains("503")
                .doesNotContain("email@example.com", "verification-token", "password", "cookie-value");
        assertThat(registry.counter(
                "discord.notification", "type", "operational_alert", "outcome", "attempted").count()).isEqualTo(1);
        assertThat(registry.counter(
                "discord.notification", "type", "operational_alert", "outcome", "succeeded").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 예외 알림 호출은 제목과 요청 경로·메서드 계약을 유지한다")
    void keepsExistingExceptionNotificationPayload() {
        AtomicReference<String> serializedBody = new AtomicReference<>();
        DiscordWebhookService service = service(WEBHOOK_URL, request -> {
            serializedBody.set(serializeBody(request));
            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        }, Duration.ofSeconds(1));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/comments");

        service.sendExceptionNotification(new IllegalStateException("do-not-send-this-message"), request);

        assertThat(serializedBody.get())
                .contains("Unhandled Exception")
                .contains("Exception Occurred!")
                .contains("/api/comments")
                .contains("POST")
                .contains("IllegalStateException")
                .doesNotContain("do-not-send-this-message", WEBHOOK_URL);
    }

    @Test
    @DisplayName("Discord 4xx, 429, 5xx 응답은 전송 실패로 소비하고 API 호출을 정상 반환한다")
    void consumesWebhookHttpFailures() {
        for (HttpStatus status : List.of(
                HttpStatus.BAD_REQUEST,
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.BAD_GATEWAY)) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            DiscordWebhookService service = service(WEBHOOK_URL, request -> Mono.just(
                    ClientResponse.create(status).body("private-response-body").build()), Duration.ofSeconds(1), registry);

            service.sendOperationalAlert(alert());

            assertThat(registry.counter("discord.notification", "type", "operational_alert", "outcome", "attempted")
                    .count()).isEqualTo(1);
            assertThat(registry.counter("discord.notification", "type", "operational_alert", "outcome", "failed")
                    .count()).isEqualTo(1);
            assertThat(registry.counter("discord.notification", "type", "operational_alert", "outcome", "succeeded")
                    .count()).isZero();
        }
    }

    @Test
    @DisplayName("전송 타임아웃과 동기 호출 예외는 실패 지표에 기록하고 밖으로 전달하지 않는다")
    void consumesTimeoutAndSynchronousCallFailure() {
        SimpleMeterRegistry timeoutRegistry = new SimpleMeterRegistry();
        DiscordWebhookService timedOutService = service(
                WEBHOOK_URL,
                request -> Mono.never(),
                Duration.ofMillis(10),
                timeoutRegistry);

        timedOutService.sendOperationalAlert(alert());
        Mono.delay(Duration.ofMillis(50)).block();

        assertThat(timeoutRegistry.counter(
                "discord.notification", "type", "operational_alert", "outcome", "failed").count()).isEqualTo(1);

        SimpleMeterRegistry synchronousRegistry = new SimpleMeterRegistry();
        WebClient.Builder failingBuilder = mock(WebClient.Builder.class);
        given(failingBuilder.build()).willThrow(new IllegalStateException("private synchronous failure"));
        DiscordWebhookService synchronousFailureService = new DiscordWebhookService(
                WEBHOOK_URL,
                failingBuilder,
                synchronousRegistry,
                Duration.ofSeconds(1));

        synchronousFailureService.sendOperationalAlert(alert());

        assertThat(synchronousRegistry.counter(
                "discord.notification", "type", "operational_alert", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("비동기 전송 실패 로그에는 웹훅 주소와 응답 본문을 남기지 않는다")
    void doesNotLogSensitiveWebhookFailureDetails() {
        String privateResponseBody = "email@example.com verification-token password cookie-value";
        DiscordWebhookService service = service(WEBHOOK_URL, request -> Mono.just(
                ClientResponse.create(HttpStatus.BAD_GATEWAY)
                        .body(privateResponseBody)
                        .build()), Duration.ofSeconds(1));
        Logger logger = (Logger) LoggerFactory.getLogger(DiscordWebhookService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);

        try {
            service.sendOperationalAlert(alert());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(privateResponseBody, WEBHOOK_URL, "email@example.com", "verification-token"));
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("event=discord_notification", "outcome=failed", "response_status=502"));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    @DisplayName("연결 예외의 원본 메시지와 웹훅 주소를 로그에 남기지 않고 실패로 기록한다")
    void sanitizesConnectionFailure() {
        String sensitiveMessage = "private-connection-message " + WEBHOOK_URL;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DiscordWebhookService service = service(
                WEBHOOK_URL,
                request -> Mono.error(new IllegalStateException(sensitiveMessage)),
                Duration.ofSeconds(1),
                registry);
        Logger logger = (Logger) LoggerFactory.getLogger(DiscordWebhookService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.sendOperationalAlert(alert());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(sensitiveMessage, WEBHOOK_URL));
        assertThat(registry.counter(
                "discord.notification", "type", "operational_alert", "outcome", "failed").count()).isEqualTo(1);
    }

    private DiscordWebhookService service(
            String webhookUrl,
            ExchangeFunction exchangeFunction,
            Duration timeout) {
        return service(webhookUrl, exchangeFunction, timeout, new SimpleMeterRegistry());
    }

    private DiscordWebhookService service(
            String webhookUrl,
            ExchangeFunction exchangeFunction,
            Duration timeout,
            SimpleMeterRegistry registry) {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        given(builder.build()).willReturn(WebClient.builder().exchangeFunction(exchangeFunction).build());
        return new DiscordWebhookService(webhookUrl, builder, registry, timeout);
    }

    private String serializeBody(ClientRequest request) {
        MockClientHttpRequest mockRequest = new MockClientHttpRequest(HttpMethod.POST, request.url());
        BodyInserter.Context context = new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        };

        request.body().insert(mockRequest, context).block();
        return mockRequest.getBodyAsString().block();
    }

    private OperationalAlert alert() {
        return new OperationalAlert(
                "Redis 장애",
                "production",
                LocalDateTime.of(2026, 9, 30, 12, 34, 56),
                "/api/auth/signup",
                "POST",
                "가입 토큰 확인",
                "RedisConnectionFailureException",
                503);
    }
}
