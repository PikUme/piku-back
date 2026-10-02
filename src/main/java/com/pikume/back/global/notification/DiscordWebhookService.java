package com.pikume.back.global.notification;

import com.pikume.back.global.notification.dto.DiscordEmbed;
import com.pikume.back.global.notification.dto.DiscordMessage;
import com.pikume.back.global.notification.dto.EmbedField;
import com.pikume.back.global.notification.dto.OperationalAlert;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Color;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Profile("prod")
public class DiscordWebhookService {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    private static final String UNCONFIGURED_WEBHOOK_URL = "discord_webhook_url";

    private final String webhookUrl;
    private final WebClient.Builder webClientBuilder;
    private final MeterRegistry meterRegistry;
    private final Duration sendTimeout;

    @Autowired
    public DiscordWebhookService(
            @Value("${discord.webhook.url}") String webhookUrl,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {
        this(webhookUrl, webClientBuilder, meterRegistry, SEND_TIMEOUT);
    }

    DiscordWebhookService(
            String webhookUrl,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry,
            Duration sendTimeout) {
        this.webhookUrl = webhookUrl;
        this.webClientBuilder = webClientBuilder;
        this.meterRegistry = meterRegistry;
        this.sendTimeout = sendTimeout;
    }

    @PostConstruct
    void validateWebhookConfiguration() {
        if (webhookUrl == null
                || webhookUrl.isBlank()
                || UNCONFIGURED_WEBHOOK_URL.equals(webhookUrl.trim())) {
            throw new IllegalStateException("discord.webhook.url must be configured for prod profile");
        }
    }

    public void sendExceptionNotification(Exception exception, HttpServletRequest request) {
        String requestUri = safeRequestValue(request, true);
        String requestMethod = safeRequestValue(request, false);
        String errorType = exception == null ? "UnknownException" : exception.getClass().getSimpleName();
        List<EmbedField> fields = List.of(
                new EmbedField("Request-URI", requestUri, false),
                new EmbedField("Request-Method", requestMethod, false),
                new EmbedField("Exception", errorType, false));
        DiscordMessage message = discordMessage("Unhandled Exception", "🚨 Exception Occurred!", fields);

        send(message, "exception_notification");
    }

    public void sendOperationalAlert(OperationalAlert alert) {
        if (alert == null) {
            logFailure("operational_alert", "InvalidAlert", null);
            return;
        }

        List<EmbedField> fields = List.of(
                new EmbedField("Environment", safeValue(alert.environment()), false),
                new EmbedField("Occurred At (KST)", safeValue(alert.occurredAt()), false),
                new EmbedField("API Path", safeValue(alert.requestPath()), false),
                new EmbedField("API Method", safeValue(alert.requestMethod()), false),
                new EmbedField("Processing Stage", safeValue(alert.processingStage()), false),
                new EmbedField("Error Type", safeValue(alert.errorType()), false),
                new EmbedField("Response Status", safeValue(alert.responseStatus()), false));
        DiscordMessage message = discordMessage(
                safeValue(alert.title()),
                "⚠️ Operational alert",
                fields);

        send(message, "operational_alert");
    }

    private static String safeRequestValue(HttpServletRequest request, boolean requestUri) {
        if (request == null) {
            return "unknown";
        }

        try {
            String value = requestUri ? request.getRequestURI() : request.getMethod();
            return safeValue(value);
        } catch (RuntimeException exception) {
            return "unavailable";
        }
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "unknown";
        }

        String text = String.valueOf(value);
        if (text.isBlank()) {
            return "unknown";
        }

        return text.length() <= 1024 ? text : text.substring(0, 1024);
    }

    private static DiscordMessage discordMessage(String content, String embedTitle, List<EmbedField> fields) {
        DiscordEmbed embed = new DiscordEmbed(
                embedTitle,
                null,
                Color.RED.getRGB() & 0xFFFFFF,
                fields);

        return new DiscordMessage(content, List.of(embed));
    }

    private void send(DiscordMessage message, String notificationType) {
        recordMetric(notificationType, "attempted");
        log.info("event=discord_notification outcome=attempted type={}", notificationType);

        try {
            webClientBuilder.build()
                    .post()
                    .uri(webhookUrl)
                    .bodyValue(message)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .onErrorMap(this::sanitizeFailure)
                    .timeout(sendTimeout)
                    .doOnSuccess(ignored -> {
                        recordMetric(notificationType, "succeeded");
                        log.info("event=discord_notification outcome=succeeded type={}", notificationType);
                    })
                    .onErrorResume(error -> {
                        logFailure(notificationType, safeFailureType(error), safeFailureStatus(error));
                        recordMetric(notificationType, "failed");
                        return Mono.empty();
                    })
                    .subscribe();
        } catch (RuntimeException exception) {
            logFailure(notificationType, exception.getClass().getSimpleName(), null);
            recordMetric(notificationType, "failed");
        }
    }

    private Throwable sanitizeFailure(Throwable failure) {
        Integer responseStatus = null;
        if (failure instanceof WebClientResponseException responseException) {
            responseStatus = responseException.getStatusCode().value();
        }

        return new SanitizedNotificationFailure(safeFailureType(failure), responseStatus);
    }

    private static String safeFailureType(Throwable failure) {
        if (failure instanceof SanitizedNotificationFailure sanitizedFailure) {
            return sanitizedFailure.failureType();
        }
        if (failure instanceof TimeoutException) {
            return "TimeoutException";
        }
        if (failure instanceof WebClientResponseException) {
            return "WebhookHttpFailure";
        }

        return failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
    }

    private static Integer safeFailureStatus(Throwable failure) {
        if (failure instanceof SanitizedNotificationFailure sanitizedFailure) {
            return sanitizedFailure.responseStatus();
        }
        if (failure instanceof WebClientResponseException responseException) {
            HttpStatusCode statusCode = responseException.getStatusCode();
            return statusCode.value();
        }

        return null;
    }

    private void recordMetric(String notificationType, String outcome) {
        try {
            meterRegistry.counter(
                    "discord.notification",
                    "type", notificationType,
                    "outcome", outcome).increment();
        } catch (RuntimeException exception) {
            log.warn("event=discord_notification_metric outcome=failed type={}", notificationType);
        }
    }

    private void logFailure(String notificationType, String failureType, Integer responseStatus) {
        if (responseStatus == null) {
            log.warn(
                    "event=discord_notification outcome=failed type={} failure_type={}",
                    notificationType,
                    failureType);
            return;
        }

        log.warn(
                "event=discord_notification outcome=failed type={} failure_type={} response_status={}",
                notificationType,
                failureType,
                responseStatus);
    }

    private static final class SanitizedNotificationFailure extends RuntimeException {

        private final String failureType;
        private final Integer responseStatus;

        private SanitizedNotificationFailure(String failureType, Integer responseStatus) {
            super("Discord webhook notification failed");
            this.failureType = failureType;
            this.responseStatus = responseStatus;
        }

        private String failureType() {
            return failureType;
        }

        private Integer responseStatus() {
            return responseStatus;
        }
    }
}
