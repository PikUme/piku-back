package com.pikume.back.global.notification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("DiscordWebhookService")
class DiscordWebhookServiceTest {

	private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/test-token";

	@Test
	@DisplayName("전송 성공을 기록한다")
	void recordsSuccessfulNotification() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		WebClient.Builder webClientBuilder = builderFor(request ->
				Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build()));
		DiscordWebhookService service = new DiscordWebhookService(webClientBuilder, meterRegistry, WEBHOOK_URL);

		service.sendExceptionNotification(new IllegalStateException("sensitive message"), request());

		assertThat(notificationCount(meterRegistry, "success")).isEqualTo(1);
		assertThat(notificationCount(meterRegistry, "failure")).isZero();
	}

	@Test
	@DisplayName("동기 웹 클라이언트 오류를 호출자에게 전파하지 않고 기록한다")
	void isolatesSynchronousFailure() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		WebClient.Builder webClientBuilder = mock(WebClient.Builder.class);
		given(webClientBuilder.build()).willThrow(new IllegalStateException("sensitive message"));
		DiscordWebhookService service = new DiscordWebhookService(webClientBuilder, meterRegistry, WEBHOOK_URL);

		assertThatCode(() -> service.sendExceptionNotification(
				new IllegalStateException("sensitive exception"), request()))
				.doesNotThrowAnyException();

		assertThat(failureCount(meterRegistry)).isEqualTo(1);
	}

	@Test
	@DisplayName("비동기 전송 실패를 호출자에게 전파하지 않고 기록한다")
	void isolatesAsynchronousFailure() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		WebClient.Builder webClientBuilder = builderFor(request ->
				Mono.error(new IllegalStateException("sensitive response")));
		DiscordWebhookService service = new DiscordWebhookService(webClientBuilder, meterRegistry, WEBHOOK_URL);

		assertThatCode(() -> service.sendExceptionNotification(
				new IllegalStateException("sensitive exception"), request()))
				.doesNotThrowAnyException();

		assertThat(failureCount(meterRegistry)).isEqualTo(1);
	}

	@Test
	@DisplayName("전송이 5초 안에 끝나지 않으면 timeout 실패 지표를 기록한다")
	void timesOutUnresponsiveWebhook() throws InterruptedException {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		CountDownLatch requestStarted = new CountDownLatch(1);
		WebClient.Builder webClientBuilder = builderFor(request -> {
			requestStarted.countDown();
			return Mono.never();
		});
		DiscordWebhookService service = new DiscordWebhookService(webClientBuilder, meterRegistry, WEBHOOK_URL);
		long startedAt = System.nanoTime();

		service.sendExceptionNotification(new IllegalStateException("sensitive exception"), request());

		assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(awaitFailureCount(meterRegistry, Duration.ofSeconds(7))).isEqualTo(1);
		assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(4500);
	}

	@Test
	@DisplayName("운영 웹훅 설정이 없거나 HTTPS URL이 아니면 서비스 생성을 거부한다")
	void rejectsInvalidProductionWebhookConfiguration() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

		assertThatThrownBy(() -> new DiscordWebhookService(WebClient.builder(), meterRegistry, " "))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("discord.webhook.url must be configured in the prod profile");
		assertThatThrownBy(() -> new DiscordWebhookService(
					WebClient.builder(), meterRegistry, "http://discord.com/api/webhooks/test"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("discord.webhook.url must be an absolute HTTPS URL");
	}

	private WebClient.Builder builderFor(ExchangeFunction exchangeFunction) {
		return WebClient.builder().exchangeFunction(exchangeFunction);
	}

	private MockHttpServletRequest request() {
		return new MockHttpServletRequest("GET", "/api/test");
	}

	private double failureCount(SimpleMeterRegistry meterRegistry) {
		return notificationCount(meterRegistry, "failure");
	}

	private double notificationCount(SimpleMeterRegistry meterRegistry, String outcome) {
		var counter = meterRegistry.find("discord.webhook.notifications")
				.tag("outcome", outcome)
				.counter();
		return counter == null ? 0 : counter.count();
	}

	private double awaitFailureCount(SimpleMeterRegistry meterRegistry, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (failureCount(meterRegistry) > 0) {
				return failureCount(meterRegistry);
			}
			Thread.sleep(25);
		}
		return failureCount(meterRegistry);
	}
}
