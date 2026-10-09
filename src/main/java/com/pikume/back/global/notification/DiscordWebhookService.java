package com.pikume.back.global.notification;

import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.pikume.back.global.notification.dto.DiscordEmbed;
import com.pikume.back.global.notification.dto.DiscordMessage;
import com.pikume.back.global.notification.dto.EmbedField;
import com.pikume.back.global.notification.dto.OperationalAlert;

import java.awt.Color;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@Profile("prod")
public class DiscordWebhookService {

    private static final String NOTIFICATION_METRIC = "discord.webhook.notifications";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final String webhookUrl;
    private final WebClient.Builder webClientBuilder;
    private final MeterRegistry meterRegistry;

    public DiscordWebhookService(
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry,
            @Value("${discord.webhook.url:}") String webhookUrl) {
        this.webClientBuilder = webClientBuilder;
        this.meterRegistry = meterRegistry;
        this.webhookUrl = validateWebhookUrl(webhookUrl);
    }

    public void sendExceptionNotification(Exception e, HttpServletRequest request) {
        try {
            send(getDiscordMessage(e, request), "exception_notification");
        } catch (RuntimeException failure) {
            recordFailure("exception_notification", failure);
        }
    }

    public void sendOperationalAlert(OperationalAlert alert) {
        if (alert == null) {
            recordFailure("operational_alert", new IllegalArgumentException("Operational alert is required"));
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
        DiscordEmbed embed = new DiscordEmbed(
                "⚠️ Operational alert",
                null,
                Color.RED.getRGB() & 0xFFFFFF,
                fields);
        send(new DiscordMessage(safeValue(alert.title()), List.of(embed)), "operational_alert");
    }

    private void send(DiscordMessage discordMessage, String notificationType) {
        try {
            webClientBuilder.build()
                    .post()
                    .uri(webhookUrl)
                    .bodyValue(discordMessage)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(REQUEST_TIMEOUT)
                    .subscribe(
                            ignored -> { },
                            failure -> recordFailure(notificationType, failure),
                            () -> recordSuccess(notificationType));
        } catch (RuntimeException failure) {
            recordFailure(notificationType, failure);
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

    private String validateWebhookUrl(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalStateException("discord.webhook.url must be configured in the prod profile");
        }

        try {
            URI uri = URI.create(configuredUrl);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException("discord.webhook.url must be an absolute HTTPS URL");
            }
        } catch (IllegalArgumentException invalidUrl) {
            throw new IllegalStateException("discord.webhook.url must be a valid HTTPS URL");
        }
        return configuredUrl;
    }

    private void recordSuccess(String notificationType) {
        meterRegistry.counter(NOTIFICATION_METRIC, "outcome", "success").increment();
        log.debug("event={} outcome=success", notificationType);
    }

    private void recordFailure(String notificationType, Throwable failure) {
        meterRegistry.counter(NOTIFICATION_METRIC, "outcome", "failure").increment();
        log.error("event={} outcome=failed exception={}", notificationType,
                failure.getClass().getSimpleName());
    }

    private static DiscordMessage getDiscordMessage(Exception e, HttpServletRequest request) {
        List<EmbedField> fields = List.of(
                new EmbedField("Request-URI", request.getRequestURI(), false),
                new EmbedField("Request-Method", request.getMethod(), false),
                new EmbedField("Exception", e.getClass().getSimpleName(), false));

        DiscordEmbed embed = new DiscordEmbed(
                "🚨 Exception Occurred!",
                null,
                Color.RED.getRGB() & 0xFFFFFF,
                fields
        );

        return new DiscordMessage("Unhandled Exception", List.of(embed));
    }

}
