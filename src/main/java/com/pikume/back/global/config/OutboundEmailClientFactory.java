package com.pikume.back.global.config;

import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

public final class OutboundEmailClientFactory {
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

	private OutboundEmailClientFactory() {
	}

	public static WebClient create(String url, String bearerToken) {
		HttpClient httpClient = HttpClient.create()
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(CONNECT_TIMEOUT.toMillis()))
				.responseTimeout(RESPONSE_TIMEOUT)
				.followRedirect(false)
				.disableRetry(true);

		WebClient.Builder builder = WebClient.builder()
				.baseUrl(url)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));

		if (bearerToken != null) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
		}

		return builder.build();
	}
}
