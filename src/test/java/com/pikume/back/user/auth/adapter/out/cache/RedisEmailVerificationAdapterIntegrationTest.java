package com.pikume.back.user.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort.VerificationStatus;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
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
		adapter.reserve(emailKey, generation, codeHash);
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

		assertThat(adapter.reserve(emailKey, "replacement-generation", EmailVerificationService.hash("222222")))
				.isTrue();

		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
	}

	@Test
	void failedSmtpAfterAcceptedResendLeavesTheOldTokenInvalidated() {
		String email = "smtp-failure-resend@example.com";
		String emailKey = EmailVerificationService.hash(email);
		verifyCode(emailKey, "111111", "verified-generation");
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
		adapter.reserve(emailKey, "generation", codeHash);
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
		adapter.reserve(emailKey, "old-generation", firstCode);
		adapter.reserve(emailKey, "new-generation", secondCode);

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
		adapter.reserve(emailKey, "generation", codeHash);
		adapter.activate(emailKey, "generation");

		assertThat(adapter.verify(emailKey, EmailVerificationService.hash("654321"), "version", TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.MISMATCH);
		assertThat(adapter.loadProof(emailKey, TOKEN_HASH)).isEmpty();
	}

	private static void verifyCode(String emailKey, String code, String generation) {
		String codeHash = EmailVerificationService.hash(code);
		adapter.reserve(emailKey, generation, codeHash);
		adapter.activate(emailKey, generation);
		assertThat(adapter.verify(emailKey, codeHash, UUID.randomUUID().toString(), TOKEN_HASH).status())
				.isEqualTo(VerificationStatus.VERIFIED);
	}

	private static String key(String emailKey, String suffix) {
		return "signup-email:{" + emailKey + "}" + suffix;
	}

	private static LocalDateTime kstDateTime(long millis) {
		return LocalDateTime.of(1970, 1, 1, 9, 0).plus(millis, ChronoUnit.MILLIS);
	}
}
