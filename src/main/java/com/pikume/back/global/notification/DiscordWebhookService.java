package com.pikume.back.global.notification;

import com.pikume.back.global.logging.RequestIdContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.pikume.back.global.notification.dto.DiscordEmbed;
import com.pikume.back.global.notification.dto.DiscordMessage;
import com.pikume.back.global.notification.dto.EmbedField;

import java.awt.Color;
import java.util.List;

@Slf4j
@Service
@Profile("prod")
@RequiredArgsConstructor
public class DiscordWebhookService {

    @Value("${discord.webhook.url}")
    private String webhookUrl;

    private final WebClient.Builder webClientBuilder;

    public void sendExceptionNotification(Exception e, HttpServletRequest request) {
        log.debug("event=exception_notification outcome=started");
        DiscordMessage discordMessage = getDiscordMessage(e, request);
        RequestIdContext requestContext = RequestIdContext.capture();

        webClientBuilder.build()
            .post()
            .uri(webhookUrl)
            .bodyValue(discordMessage)
            .retrieve()
            .bodyToMono(Void.class)
            .doOnSuccess(requestContext.wrap(v -> log.debug("event=exception_notification outcome=success")))
            .subscribe(v -> { }, requestContext.wrap(error ->
                    log.error("event=exception_notification outcome=failed exception={}",
                            error.getClass().getSimpleName())));
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
