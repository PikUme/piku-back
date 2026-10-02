package com.pikume.back.user.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisEmailVerificationAdapterIntegrationTest {

	private static final DefaultRedisScript<Long> REDIS_TIME_MILLIS =
			new DefaultRedisScript<>(
					"local t=redis.call('TIME'); return t[1]*1000+math.floor(t[2]/1000)", Long.class);

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:latest")
			.withExposedPorts(6379);
	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redis;
	private static RedisEmailVerificationAdapter adapter;

	@BeforeAll
	static void connect() {
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		redis = new StringRedisTemplate(connectionFactory);
		redis.afterPropertiesSet();
		adapter = new RedisEmailVerificationAdapter(redis);
	}

	@AfterAll
	static void closeConnection() {
		if (connectionFactory != null) connectionFactory.destroy();
	}

	@AfterEach
	void clearTestKeys() {
		redis.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void fifthWrongCodeLocksTheGenerationAndSixthAttemptRemainsLocked() {
		String emailKey = EmailVerificationServiceHash.hash("lock@example.com");
		String code = "123456";
		var reservation = adapter.prepare(emailKey, UUID.randomUUID().toString(),
				EmailVerificationServiceHash.hash(code), 5, 60);
		assertThat(adapter.activate(emailKey, reservation.generation(), EmailVerificationServiceHash.hash(code))).isTrue();

		for (int attempt = 1; attempt <= 5; attempt++) {
			var result = adapter.verify(emailKey, EmailVerificationServiceHash.hash("654321"), "token-hash", "raw-token", 5);
			assertThat(result.failure()).isEqualTo(attempt == 5
					? EmailVerificationFailure.ATTEMPTS_EXHAUSTED : EmailVerificationFailure.CODE_MISMATCH);
		}
		assertThat(adapter.verify(emailKey, EmailVerificationServiceHash.hash(code), "token-hash", "raw-token", 5)
				.failure()).isEqualTo(EmailVerificationFailure.ATTEMPTS_EXHAUSTED);
	}

	@Test
	void sixthSendIsRateLimitedAndDoesNotReplaceExistingProof() {
		String emailKey = EmailVerificationServiceHash.hash("send@example.com");
		String tokenHash = "proof-hash";
		String rawToken = "proof-token";
		LocalDateTime fifthSendRetryAt = null;
		for (int send = 0; send < 5; send++) {
			String generation = UUID.randomUUID().toString();
			var reservation = adapter.prepare(emailKey, generation,
					EmailVerificationServiceHash.hash("123456"), 5, 60);
			fifthSendRetryAt = reservation.resendAvailableAt();
			adapter.activate(emailKey, reservation.generation(), EmailVerificationServiceHash.hash("123456"));
			var verified = adapter.verify(emailKey, EmailVerificationServiceHash.hash("123456"),
					tokenHash, rawToken, 5);
			assertThat(verified.failure()).isNull();
			assertThat(adapter.isTokenValid(emailKey, tokenHash)).isTrue();
			String sendsKey = "signup-email:{" + emailKey + "}:sends";
			redis.opsForZSet().add(sendsKey, generation, System.currentTimeMillis() - 61_000);
		}
		assertThat(fifthSendRetryAt).isAfter(LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusSeconds(60));

		assertThatThrownBy(() -> adapter.prepare(emailKey, UUID.randomUUID().toString(),
				EmailVerificationServiceHash.hash("999999"), 5, 60))
				.isInstanceOfSatisfying(EmailVerificationException.class,
					exception -> assertThat(exception.getReason()).isEqualTo(EmailVerificationFailure.RATE_LIMITED));
		assertThat(adapter.isTokenValid(emailKey, tokenHash)).isTrue();
	}

	@Test
	void staleDeliveryCannotActivateOrReplaceNewerGeneration() {
		String emailKey = EmailVerificationServiceHash.hash("generation@example.com");
		var first = adapter.prepare(emailKey, "old-generation", EmailVerificationServiceHash.hash("111111"), 5, 60);
		String sendsKey = "signup-email:{" + emailKey + "}:sends";
		redis.opsForZSet().add(sendsKey, "old-generation", System.currentTimeMillis() - 61_000);
		var second = adapter.prepare(emailKey, "new-generation", EmailVerificationServiceHash.hash("222222"), 5, 60);

		assertThat(adapter.activate(emailKey, first.generation(), EmailVerificationServiceHash.hash("111111"))).isFalse();
		assertThat(adapter.activate(emailKey, second.generation(), EmailVerificationServiceHash.hash("222222"))).isTrue();
		assertThat(adapter.verify(emailKey, EmailVerificationServiceHash.hash("222222"), "token-hash", "raw-token", 5)
				.failure()).isNull();
	}

	@Test
	void concurrentFirstSendsAllowOnlyOneReservation() throws Exception {
		String emailKey = EmailVerificationServiceHash.hash("first-send@example.com");
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> tryPrepare(emailKey, "first-generation"));
			var second = executor.submit(() -> tryPrepare(emailKey, "second-generation"));
			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("accepted", "rate_limited");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentVerificationIssuesOnlyOneTokenAndUsesRedisDeadlines() throws Exception {
		String emailKey = EmailVerificationServiceHash.hash("verify-race@example.com");
		String codeHash = EmailVerificationServiceHash.hash("123456");
		var reservation = adapter.prepare(emailKey, "generation", codeHash, 5, 60);
		String codeKey = "signup-email:{" + emailKey + "}:auth";
		assertThat(redis.getExpire(codeKey)).isBetween(299L, 300L);
		assertThat(reservation.expiresAt()).isEqualTo(kstDateTime(Long.parseLong(
				redis.opsForHash().get(codeKey, "deadline").toString())));
		assertThat(adapter.activate(emailKey, reservation.generation(), codeHash)).isTrue();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> adapter.verify(emailKey, codeHash, "first-token-hash", "first-token", 5));
			var second = executor.submit(() -> adapter.verify(emailKey, codeHash, "second-token-hash", "second-token", 5));
			var results = List.of(first.get(), second.get());
			assertThat(results.stream().filter(result -> result.failure() == null).count()).isEqualTo(1);
			assertThat(results.stream().filter(result -> result.failure() != null)
					.map(result -> result.failure())).containsExactly(EmailVerificationFailure.VERIFICATION_INVALID);
			String proofKey = "signup-email:{" + emailKey + "}:proof";
			assertThat(redis.getExpire(proofKey)).isBetween(599L, 600L);
			long redisNow = redis.execute(REDIS_TIME_MILLIS, List.of());
			var successful = results.stream().filter(result -> result.failure() == null).findFirst().orElseThrow();
			assertThat(successful.result().expiresAt()).isAfterOrEqualTo(kstDateTime(redisNow + 599_000));
			assertThat(successful.result().expiresAt()).isBeforeOrEqualTo(kstDateTime(redisNow + 601_000));
		} finally {
			executor.shutdownNow();
		}
	}

	private String tryPrepare(String emailKey, String generation) {
		try {
			adapter.prepare(emailKey, generation, EmailVerificationServiceHash.hash("123456"), 5, 60);
			return "accepted";
		} catch (EmailVerificationException exception) {
			assertThat(exception.getReason()).isEqualTo(EmailVerificationFailure.RATE_LIMITED);
			return "rate_limited";
		}
	}

	private static LocalDateTime kstDateTime(long millis) {
		return LocalDateTime.of(1970, 1, 1, 9, 0).plus(millis, ChronoUnit.MILLIS);
	}

	private static final class EmailVerificationServiceHash {
		private static String hash(String value) {
			return EmailVerificationService.hash(value);
		}
	}
}
