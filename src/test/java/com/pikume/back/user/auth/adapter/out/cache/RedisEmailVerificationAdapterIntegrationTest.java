package com.pikume.back.user.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort.VerificationStatus;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort.ReservationStatus;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisEmailVerificationAdapterIntegrationTest {

	private static final String TOKEN_HASH = EmailVerificationService.hash("signup-token");

	private static final DefaultRedisScript<Long> REDIS_TIME_MILLIS = new DefaultRedisScript<>(
			"local t=redis.call('TIME'); return t[1]*1000+math.floor(t[2]/1000)", Long.class);

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
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
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
	}

	@AfterEach
	void clearTestKeys() {
		redis.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void codeAndVerifiedProofUseRedisServerDeadlinesWithFiveAndTenMinuteTtls() {
		String emailKey = EmailVerificationService.hash("signup@example.com");
		String codeHash = EmailVerificationService.hash("123456");
		String generation = UUID.randomUUID().toString();
		assertThat(reserve(emailKey, generation, codeHash).status()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(adapter.activate(emailKey, generation)).isPresent();
		String codeKey = key(emailKey, ":auth");
		assertThat(redis.getExpire(codeKey)).isBetween(299L, 300L);

		long redisNow = redis.execute(REDIS_TIME_MILLIS, List.of());
		assertThat(adapter.verify(emailKey, codeHash, "proof-version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.VERIFIED);

		SignupEmailProof proof = adapter.loadProof(emailKey, TOKEN_HASH).orElseThrow();
		assertThat(adapter.loadProof(emailKey, EmailVerificationService.hash("wrong-token"))).isEmpty();
		assertThat(adapter.loadProof(EmailVerificationService.hash("different@example.com"), TOKEN_HASH)).isEmpty();
		assertThat(proof.expiresAt()).isBetween(kstDateTime(redisNow + 599_000), kstDateTime(redisNow + 601_000));
		assertThat(redis.getExpire(key(emailKey, ":proof"))).isBetween(599L, 600L);
	}

	@Test
	void acceptedResendInvalidatesThePreviouslyIssuedTokenBeforeDeliveryCompletes() {
		String emailKey = EmailVerificationService.hash("resend@example.com");
		verifyCode(emailKey, "111111", "verified-generation");
		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isPresent();
		redis.delete(key(emailKey, ":send-cooldown"));

		assertThat(reserve(emailKey, "replacement-generation", EmailVerificationService.hash("222222")).status())
				.isEqualTo(ReservationStatus.RESERVED);

		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
	}

	@Test
	void failedSmtpAfterAcceptedResendLeavesTheOldTokenInvalidated() {
		String email = "smtp-failure-resend@example.com";
		String emailKey = EmailVerificationService.hash(email);
		verifyCode(emailKey, "111111", "verified-generation");
		redis.delete(key(emailKey, ":send-cooldown"));
		QueryAllowedEmailUseCase allowedEmails = Mockito.mock(QueryAllowedEmailUseCase.class);
		BDDMockito.given(allowedEmails.isEmailAllowed(email)).willReturn(true);
		IssueVerificationEmailPort emailSender = Mockito.mock(IssueVerificationEmailPort.class);
		BDDMockito.willThrow(new IllegalStateException("SMTP unavailable"))
				.given(emailSender).deliverVerificationCode(ArgumentMatchers.eq(email), ArgumentMatchers.anyString());
		EmailVerificationService service = new EmailVerificationService(adapter, emailSender, allowedEmails);

		assertThatThrownBy(() -> service.sendSignUpVerificationEmail(email))
				.isInstanceOf(EmailVerificationException.class);

		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
	}

	@Test
	void expiredTokenProofIsRemovedAndCannotBeLoaded() {
		String emailKey = EmailVerificationService.hash("expired-proof@example.com");
		verifyCode(emailKey, "123456", "generation");
		String proofKey = key(emailKey, ":proof");
		redis.opsForHash().put(proofKey, "expiresAt", "1");

		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
		assertThat(redis.hasKey(proofKey)).isFalse();
	}

	@Test
	void concurrentCodeVerificationCanCreateOnlyOneEmailProof() throws Exception {
		String emailKey = EmailVerificationService.hash("verify-race@example.com");
		String codeHash = EmailVerificationService.hash("123456");
		reserve(emailKey, "generation", codeHash);
		adapter.activate(emailKey, "generation");
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> adapter.verify(emailKey, codeHash, "first-version", TOKEN_HASH));
			var second = executor.submit(() -> adapter.verify(emailKey, codeHash, "second-version", TOKEN_HASH));
			List<VerificationStatus> results = List.of(first.get().status(), second.get().status());

			assertThat(results).containsExactlyInAnyOrder(VerificationStatus.VERIFIED, VerificationStatus.NOT_FOUND);
			assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isPresent();
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void staleSmtpCompletionCannotActivateANewerCodeGeneration() {
		String emailKey = EmailVerificationService.hash("late-mail@example.com");
		String firstCode = EmailVerificationService.hash("111111");
		String secondCode = EmailVerificationService.hash("222222");
		reserve(emailKey, "old-generation", firstCode);
		redis.delete(key(emailKey, ":send-cooldown"));
		reserve(emailKey, "new-generation", secondCode);

		assertThat(adapter.activate(emailKey, "old-generation")).isEmpty();
		assertThat(adapter.activate(emailKey, "new-generation")).isPresent();
		assertThat(adapter.verify(emailKey, firstCode, "version-1", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.MISMATCH);
		assertThat(adapter.verify(emailKey, secondCode, "version-2", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.VERIFIED);
	}

	@Test
	void conditionalCleanupCannotDeleteANewerVerifiedProof() {
		String emailKey = EmailVerificationService.hash("proof-version@example.com");
		verifyCode(emailKey, "111111", "first-generation");
		SignupEmailProof firstProof = adapter.loadProof(emailKey, TOKEN_HASH).orElseThrow();
		verifyCode(emailKey, "222222", "second-generation");
		SignupEmailProof secondProof = adapter.loadProof(emailKey, TOKEN_HASH).orElseThrow();

		assertThat(adapter.removeProofIfVersionMatches(emailKey, firstProof.version())).isFalse();
		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).contains(secondProof);
	}

	@Test
	void wrongCodeDoesNotCreateEmailProof() {
		String emailKey = EmailVerificationService.hash("wrong-code@example.com");
		String codeHash = EmailVerificationService.hash("123456");
		reserve(emailKey, "generation", codeHash);
		adapter.activate(emailKey, "generation");

		assertThat(adapter.verify(emailKey, EmailVerificationService.hash("654321"), "version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.MISMATCH);
		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
	}

	@Test
	void resendCooldownDenialPreservesCurrentCodeAndProof() {
		String emailKey = EmailVerificationService.hash("cooldown@example.com");
		String codeHash = EmailVerificationService.hash("123456");
		String firstGeneration = "first-generation";
		reserve(emailKey, firstGeneration, codeHash);
		String proofKey = key(emailKey, ":proof");
		redis.opsForHash().put(proofKey, "version", "existing-version");
		redis.opsForHash().put(proofKey, "tokenHash", TOKEN_HASH);
		redis.opsForHash().put(proofKey, "expiresAt", Long.toString(redisTimeMillis() + 600_000));
		String authKey = key(emailKey, ":auth");
		var existingCode = redis.opsForHash().entries(authKey);

		EmailVerificationStorePort.ReservationResult denied = reserve(emailKey,
				"denied-generation", EmailVerificationService.hash("654321"));

		assertThat(denied.status()).isEqualTo(ReservationStatus.RATE_LIMITED);
		assertThat(denied.resendAvailableAt()).isBetween(kstDateTime(redisTimeMillis() + 55_000),
				kstDateTime(redisTimeMillis() + 61_000));
		assertThat(redis.opsForHash().entries(authKey)).isEqualTo(existingCode);
		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isPresent();
	}

	@Test
	void fivePermittedSendsInRollingHourRejectTheSixth() {
		String emailKey = EmailVerificationService.hash("hourly-limit@example.com");
		EmailVerificationStorePort.ReservationResult first = null;
		for (int index = 0; index < 5; index++) {
			redis.delete(key(emailKey, ":send-cooldown"));
			EmailVerificationStorePort.ReservationResult reservation = reserve(emailKey, "generation-" + index,
					EmailVerificationService.hash("code-" + index));
			assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
			if (index == 0) {
				first = reservation;
			}
		}

		EmailVerificationStorePort.ReservationResult denied = reserve(emailKey, "sixth-generation",
				EmailVerificationService.hash("sixth-code"));

		assertThat(denied.status()).isEqualTo(ReservationStatus.RATE_LIMITED);
		assertThat(denied.resendAvailableAt()).isBetween(first.resendAvailableAt().plusMinutes(59),
				first.resendAvailableAt().plusMinutes(61));
	}

	@Test
	void fifthAllowedReservationReturnsTheHourlyWindowDeadline() {
		String emailKey = EmailVerificationService.hash("fifth-success@example.com");
		long firstSendAt = seedFourRecentSends(emailKey);

		EmailVerificationStorePort.ReservationResult fifth = reserve(emailKey, "fifth-generation",
				EmailVerificationService.hash("fifth-code"));

		assertThat(fifth.status()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(fifth.resendAvailableAt()).isBetween(kstDateTime(firstSendAt + 3_355_000),
				kstDateTime(firstSendAt + 3_365_000));
	}

	@Test
	void fifthAllowedSmtpFailureReturnsTheHourlyWindowDeadline() {
		String email = "fifth-failure@example.com";
		String emailKey = EmailVerificationService.hash(email);
		long firstSendAt = seedFourRecentSends(emailKey);
		QueryAllowedEmailUseCase allowedEmails = Mockito.mock(QueryAllowedEmailUseCase.class);
		BDDMockito.given(allowedEmails.isEmailAllowed(email)).willReturn(true);
		IssueVerificationEmailPort emailSender = Mockito.mock(IssueVerificationEmailPort.class);
		BDDMockito.willThrow(new IllegalStateException("SMTP unavailable"))
				.given(emailSender).deliverVerificationCode(ArgumentMatchers.eq(email), ArgumentMatchers.anyString());
		EmailVerificationService service = new EmailVerificationService(adapter, emailSender, allowedEmails);

		assertThatThrownBy(() -> service.sendSignUpVerificationEmail(email))
				.isInstanceOfSatisfying(EmailVerificationException.class, error -> {
					assertThat(error.getReason()).isEqualTo(EmailVerificationFailure.EMAIL_SEND_FAILED);
					assertThat(error.getRetryAt()).isBetween(kstDateTime(firstSendAt + 3_355_000),
							kstDateTime(firstSendAt + 3_365_000));
				});
	}

	@Test
	void concurrentReservationsOnlyAdmitOneWithinTheCooldownAndHourlyCap() throws Exception {
		String emailKey = EmailVerificationService.hash("concurrent-reservations@example.com");
		seedFourRecentSends(emailKey);
		var executor = Executors.newFixedThreadPool(12);
		var start = new CountDownLatch(1);
		List<Future<EmailVerificationStorePort.ReservationResult>> futures = new ArrayList<>();
		try {
			for (int index = 0; index < 12; index++) {
				int request = index;
				futures.add(executor.submit(() -> {
					start.await();
					return reserve(emailKey, "concurrent-generation-" + request,
							EmailVerificationService.hash("concurrent-code-" + request));
				}));
			}
			start.countDown();
			List<ReservationStatus> statuses = new ArrayList<>();
			for (Future<EmailVerificationStorePort.ReservationResult> future : futures) {
				statuses.add(future.get().status());
			}

			assertThat(statuses).filteredOn(status -> status == ReservationStatus.RESERVED).hasSize(1);
			assertThat(statuses).filteredOn(status -> status == ReservationStatus.RATE_LIMITED).hasSize(11);
			assertThat(redis.opsForZSet().size(key(emailKey, ":send-window"))).isEqualTo(5);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void cooldownAndRollingHourBoundariesUseRedisTime() {
		String cooldownBoundaryKey = EmailVerificationService.hash("cooldown-boundary@example.com");
		redis.opsForValue().set(key(cooldownBoundaryKey, ":send-cooldown"),
				Long.toString(redisTimeMillis()));
		assertThat(reserve(cooldownBoundaryKey, "boundary-generation", EmailVerificationService.hash("123456"))
				.status()).isEqualTo(ReservationStatus.RESERVED);

		String hourBoundaryKey = EmailVerificationService.hash("hour-boundary@example.com");
		long now = redisTimeMillis();
		for (int index = 0; index < 5; index++) {
			redis.opsForZSet().add(key(hourBoundaryKey, ":send-window"), "old-send-" + index, now - 3_600_000);
		}
		assertThat(reserve(hourBoundaryKey, "hour-boundary-generation", EmailVerificationService.hash("654321"))
				.status()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(redis.opsForZSet().size(key(hourBoundaryKey, ":send-window"))).isEqualTo(1L);
	}

	@Test
	void fifthWrongCodeLocksVerificationUntilCodeExpiry() {
		String emailKey = EmailVerificationService.hash("attempt-limit@example.com");
		String codeHash = EmailVerificationService.hash("123456");
		reserve(emailKey, "generation", codeHash);
		adapter.activate(emailKey, "generation");
		String wrongHash = EmailVerificationService.hash("654321");

		for (int attempt = 0; attempt < 4; attempt++) {
			assertThat(adapter.verify(emailKey, wrongHash, "version-" + attempt, TOKEN_HASH).status())
					.isEqualTo(VerificationStatus.MISMATCH);
		}
		EmailVerificationStorePort.VerificationResult exhausted = adapter.verify(emailKey, wrongHash,
				"fifth-version", TOKEN_HASH);

		assertThat(exhausted.status()).isEqualTo(VerificationStatus.ATTEMPTS_EXHAUSTED);
		assertThat(exhausted.retryAt()).isBetween(kstDateTime(redisTimeMillis() + 55_000),
				kstDateTime(redisTimeMillis() + 61_000));
		assertThat(adapter.verify(emailKey, codeHash, "correct-version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.ATTEMPTS_EXHAUSTED);
		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
	}

	@Test
	void aNewReservationAfterAttemptLockStartsWithFreshAttempts() {
		String emailKey = EmailVerificationService.hash("attempt-reset@example.com");
		String wrongHash = EmailVerificationService.hash("wrong-code");
		String firstCodeHash = EmailVerificationService.hash("111111");
		reserve(emailKey, "first-generation", firstCodeHash);
		adapter.activate(emailKey, "first-generation");
		for (int attempt = 0; attempt < 5; attempt++) {
			adapter.verify(emailKey, wrongHash, "wrong-version-" + attempt, TOKEN_HASH);
		}
		assertThat(adapter.verify(emailKey, firstCodeHash, "locked-version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.ATTEMPTS_EXHAUSTED);

		redis.delete(key(emailKey, ":send-cooldown"));
		String secondCodeHash = EmailVerificationService.hash("222222");
		reserve(emailKey, "second-generation", secondCodeHash);
		adapter.activate(emailKey, "second-generation");

		assertThat(adapter.verify(emailKey, wrongHash, "fresh-wrong-version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.MISMATCH);
		assertThat(adapter.verify(emailKey, secondCodeHash, "fresh-correct-version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.VERIFIED);
	}

	@Test
	void exhaustedAttemptRetryTimeUsesHourlySendLimitInsteadOfCodeExpiry() {
		String emailKey = EmailVerificationService.hash("attempt-hourly-retry@example.com");
		EmailVerificationStorePort.ReservationResult first = null;
		for (int index = 0; index < 5; index++) {
			redis.delete(key(emailKey, ":send-cooldown"));
			EmailVerificationStorePort.ReservationResult reservation = reserve(emailKey, "generation-" + index,
					EmailVerificationService.hash("code-" + index));
			if (index == 0) {
				first = reservation;
			}
		}
		adapter.activate(emailKey, "generation-4");
		String wrongHash = EmailVerificationService.hash("wrong-code");
		for (int attempt = 0; attempt < 4; attempt++) {
			adapter.verify(emailKey, wrongHash, "version-" + attempt, TOKEN_HASH);
		}

		EmailVerificationStorePort.VerificationResult exhausted = adapter.verify(emailKey, wrongHash,
				"fifth-version", TOKEN_HASH);

		assertThat(exhausted.status()).isEqualTo(VerificationStatus.ATTEMPTS_EXHAUSTED);
		assertThat(exhausted.retryAt()).isBetween(first.resendAvailableAt().plusMinutes(59),
				first.resendAvailableAt().plusMinutes(60));
	}

	@Test
	void smtpFailuresForPermittedReservationsCountTowardHourlyLimit() {
		String email = "failed-send-limit@example.com";
		String emailKey = EmailVerificationService.hash(email);
		QueryAllowedEmailUseCase allowedEmails = Mockito.mock(QueryAllowedEmailUseCase.class);
		BDDMockito.given(allowedEmails.isEmailAllowed(email)).willReturn(true);
		IssueVerificationEmailPort emailSender = Mockito.mock(IssueVerificationEmailPort.class);
		BDDMockito.willThrow(new IllegalStateException("SMTP unavailable"))
				.given(emailSender).deliverVerificationCode(ArgumentMatchers.eq(email), ArgumentMatchers.anyString());
		EmailVerificationService service = new EmailVerificationService(adapter, emailSender, allowedEmails);

		for (int attempt = 0; attempt < 5; attempt++) {
			if (attempt > 0) {
				redis.delete(key(emailKey, ":send-cooldown"));
			}
			assertThatThrownBy(() -> service.sendSignUpVerificationEmail(email))
					.isInstanceOf(EmailVerificationException.class)
					.hasMessageContaining("EMAIL_SEND_FAILED");
		}
		redis.delete(key(emailKey, ":send-cooldown"));

		assertThatThrownBy(() -> service.sendSignUpVerificationEmail(email))
				.isInstanceOf(EmailVerificationException.class)
				.hasMessageContaining("RATE_LIMITED");
		BDDMockito.then(emailSender).should(Mockito.times(5))
				.deliverVerificationCode(ArgumentMatchers.eq(email), ArgumentMatchers.anyString());
	}

	private static void verifyCode(String emailKey, String code, String generation) {
		String codeHash = EmailVerificationService.hash(code);
		redis.delete(key(emailKey, ":send-cooldown"));
		reserve(emailKey, generation, codeHash);
		adapter.activate(emailKey, generation);
		assertThat(adapter.verify(emailKey, codeHash, UUID.randomUUID().toString(), TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.VERIFIED);
	}

	private static EmailVerificationStorePort.ReservationResult reserve(String emailKey, String generation,
			String codeHash) {
		return adapter.reserve(emailKey, generation, codeHash, UUID.randomUUID().toString());
	}

	private static long seedFourRecentSends(String emailKey) {
		long now = redisTimeMillis();
		for (int index = 0; index < 4; index++) {
			long sentAt = now - 240_000 + index * 60_000L;
			redis.opsForZSet().add(key(emailKey, ":send-window"), "seeded-send-" + index, sentAt);
		}
		redis.opsForValue().set(key(emailKey, ":send-cooldown"), Long.toString(now));
		return now;
	}

	private static String key(String emailKey, String suffix) {
		return "signup-email:{" + emailKey + "}" + suffix;
	}

	private static LocalDateTime kstDateTime(long millis) {
		return LocalDateTime.of(1970, 1, 1, 9, 0).plus(millis, ChronoUnit.MILLIS);
	}

	private static long redisTimeMillis() {
		return redis.execute(REDIS_TIME_MILLIS, List.of());
	}
}
