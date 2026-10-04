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
            DiscordMessage discordMessage = getDiscordMessage(e, request);

            webClientBuilder.build()
                .post()
                .uri(webhookUrl)
                .bodyValue(discordMessage)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(REQUEST_TIMEOUT)
                .subscribe(
                        ignored -> { },
                        this::recordFailure,
                        this::recordSuccess);
        } catch (RuntimeException failure) {
            recordFailure(failure);
        }
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

    private void recordSuccess() {
        meterRegistry.counter(NOTIFICATION_METRIC, "outcome", "success").increment();
        log.debug("event=exception_notification outcome=success");
    }

    private void recordFailure(Throwable failure) {
        meterRegistry.counter(NOTIFICATION_METRIC, "outcome", "failure").increment();
        log.error("event=exception_notification outcome=failed exception={}",
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
