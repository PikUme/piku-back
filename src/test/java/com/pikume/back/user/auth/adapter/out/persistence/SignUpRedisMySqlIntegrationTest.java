package com.pikume.back.user.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pikume.back.user.adapter.out.persistence.UserAccountPersistenceAdapter;
import com.pikume.back.user.adapter.out.persistence.UserJpaRepository;
import com.pikume.back.user.adapter.out.persistence.UserPersistenceAdapter;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.auth.adapter.out.cache.RedisEmailVerificationAdapter;
import com.pikume.back.user.auth.application.dto.SendEmailVerificationCommand;
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCodeCommand;
import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.CheckSignUpCharacterSelectionPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationOperationsAlertPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationPolicyPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.port.out.LoadCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.port.out.LoadVerificationPort;
import com.pikume.back.user.auth.application.port.out.ManageVerificationPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.RecordCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.port.out.SignUpTransactionPort;
import com.pikume.back.user.auth.application.service.AuthService;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.domain.service.PasswordPolicy;
import com.pikume.back.user.auth.application.dto.EmailVerificationReservation;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort.EmailVerificationAttempt;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.vo.Email;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Testcontainers
@Import({UserPersistenceAdapter.class, UserAccountPersistenceAdapter.class, SignUpTransactionAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SignUpRedisMySqlIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("email_signup_flow_test")
			.withUsername("test")
			.withPassword("test");
	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:latest")
			.withExposedPorts(6379);

	private static LettuceConnectionFactory redisConnection;
	private static StringRedisTemplate redis;

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
		properties.add("spring.datasource.username", MYSQL::getUsername);
		properties.add("spring.datasource.password", MYSQL::getPassword);
		properties.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
		properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
	}

	@BeforeAll
	static void connectRedis() {
		redisConnection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		redisConnection.afterPropertiesSet();
		redis = new StringRedisTemplate(redisConnection);
		redis.afterPropertiesSet();
	}

	@AfterAll
	static void closeRedis() {
		if (redisConnection != null) redisConnection.destroy();
	}

	@Autowired
	private UserJpaRepository users;
	@Autowired
	private UserPersistenceAdapter userPersistence;
	@Autowired
	private UserAccountPersistenceAdapter userAccounts;
	@Autowired
	private SignUpTransactionPort transactions;

	@BeforeEach
	void clearIsolatedStores() {
		users.deleteAllInBatch();
		redis.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void realRedisVerificationAndMysqlCommitConsumeOnlyTheMatchingToken() {
		EmailVerificationStorePort store = new RedisEmailVerificationAdapter(redis);
		EmailVerificationService verification = verificationService(store, userAccounts);
		String email = "signup-flow@example.com";
		String token = obtainToken(verification, email);
		AuthService signup = authService(store, userAccounts, transactions, noopAlerts());

		signup.signUp(command(email, "signup-flow-nickname", token));

		assertThat(users.findByEmail(new Email(email))).isPresent();
		assertThat(store.isTokenValid(EmailVerificationService.hash(email), EmailVerificationService.hash(token))).isFalse();
	}

	@Test
	void databaseNicknameConflictRollsBackSignupAndLeavesValidRedisProof() {
		String email = "rollback@example.com";
		transactions.register(new User("existing@example.com", "password-hash", "taken-nickname", 1L));
		EmailVerificationStorePort store = new RedisEmailVerificationAdapter(redis);
		String token = obtainToken(verificationService(store, userAccounts), email);
		AuthService signup = authService(store, userAccounts, transactions, noopAlerts());

		assertThatThrownBy(() -> signup.signUp(command(email, "taken-nickname", token)))
				.isInstanceOf(AuthException.class);
		assertThat(users.findByEmail(new Email(email))).isEmpty();
		assertThat(store.isTokenValid(EmailVerificationService.hash(email), EmailVerificationService.hash(token))).isTrue();
	}

	@Test
	void cleanupFailureKeepsCommittedSignupSuccessfulAndPreservesTheTokenUntilTtl() {
		EmailVerificationStorePort redisStore = new RedisEmailVerificationAdapter(redis);
		String email = "cleanup-failure@example.com";
		String token = obtainToken(verificationService(redisStore, userAccounts), email);
		EmailVerificationStorePort failingCleanupStore = delegateCleanupFailure(redisStore);
		EmailVerificationOperationsAlertPort alerts = mock(EmailVerificationOperationsAlertPort.class);
		AuthService signup = authService(failingCleanupStore, userAccounts, transactions, alerts);

		signup.signUp(command(email, "cleanup-nickname", token));

		assertThat(users.findByEmail(new Email(email))).isPresent();
		assertThat(redisStore.isTokenValid(EmailVerificationService.hash(email), EmailVerificationService.hash(token))).isTrue();
		verify(alerts).signupTokenCleanupFailed("IllegalStateException");
	}

	@Test
	void concurrentRequestsCanBothValidateProofButMysqlCreatesOneAccount() throws Exception {
		EmailVerificationStorePort redisStore = new RedisEmailVerificationAdapter(redis);
		String email = "concurrent-flow@example.com";
		String token = obtainToken(verificationService(redisStore, userAccounts), email);
		AtomicInteger successfulProofChecks = new AtomicInteger();
		EmailVerificationStorePort observedStore = new EmailVerificationStorePort() {
			public EmailVerificationReservation prepare(String emailKey, String generation, String codeHash, int limit, int resend) {
				return redisStore.prepare(emailKey, generation, codeHash, limit, resend);
			}
			public boolean activate(String emailKey, String generation, String codeHash) {
				return redisStore.activate(emailKey, generation, codeHash);
			}
			public EmailVerificationAttempt verify(String emailKey, String codeHash, String tokenHash, String rawToken, int attempts) {
				return redisStore.verify(emailKey, codeHash, tokenHash, rawToken, attempts);
			}
			public boolean isTokenValid(String emailKey, String tokenHash) {
				boolean valid = redisStore.isTokenValid(emailKey, tokenHash);
				if (valid) successfulProofChecks.incrementAndGet();
				return valid;
			}
			public boolean removeToken(String emailKey, String tokenHash) {
				return redisStore.removeToken(emailKey, tokenHash);
			}
		};
		CyclicBarrier bothDatabaseEntrances = new CyclicBarrier(2);
		SignUpTransactionPort synchronizedTransactions = user -> {
			try {
				bothDatabaseEntrances.await(10, TimeUnit.SECONDS);
			} catch (Exception exception) {
				throw new IllegalStateException(exception);
			}
			return transactions.register(user);
		};
		AuthService signup = authService(observedStore, userAccounts, synchronizedTransactions, noopAlerts());
		var executor = Executors.newFixedThreadPool(2);
		try {
			var one = executor.submit(() -> trySignup(signup, command(email, "concurrent-one", token)));
			var two = executor.submit(() -> trySignup(signup, command(email, "concurrent-two", token)));
			Set<String> results = Set.of(one.get(), two.get());
			assertThat(results).containsExactlyInAnyOrder("created", "rejected_duplicate_email");
			assertThat(successfulProofChecks).hasValue(2);
			assertThat(users.count()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void oldSignupCleanupCannotDeleteProofIssuedAfterTheDatabaseCommit() {
		EmailVerificationStorePort store = new RedisEmailVerificationAdapter(redis);
		String email = "new-proof@example.com";
		AtomicReference<String> newCode = new AtomicReference<>();
		EmailVerificationService verification = verificationService(store, userAccounts, newCode);
		String oldToken = obtainToken(verification, email);
		SignUpTransactionPort registerThenIssueNewProof = user -> {
			String sendsKey = "signup-email:{" + EmailVerificationService.hash(email) + "}:sends";
			redis.opsForZSet().range(sendsKey, 0, -1).forEach(member ->
					redis.opsForZSet().add(sendsKey, member, System.currentTimeMillis() - 61_000));
			verification.sendEmailCode(new SendEmailVerificationCommand(email));
			User saved = transactions.register(user);
			String newToken = verification.verifyEmailCode(new VerifyEmailCodeCommand(email, newCode.get()))
					.emailVerificationToken();
			newlyIssuedToken.set(newToken);
			return saved;
		};
		AuthService signup = authService(store, userAccounts, registerThenIssueNewProof, noopAlerts());

		signup.signUp(command(email, "new-proof-nickname", oldToken));

		assertThat(store.isTokenValid(EmailVerificationService.hash(email), EmailVerificationService.hash(oldToken))).isFalse();
		assertThat(store.isTokenValid(EmailVerificationService.hash(email),
				EmailVerificationService.hash(newlyIssuedToken.get()))).isTrue();
		assertThat(users.findByEmail(new Email(email))).isPresent();
	}

	private final AtomicReference<String> newlyIssuedToken = new AtomicReference<>();

	private static String trySignup(AuthService signup, SignUpCommand command) {
		try { signup.signUp(command); return "created"; }
		catch (AuthException exception) {
			assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);
			return "rejected_duplicate_email";
		}
	}

	private String obtainToken(EmailVerificationService verification, String email) {
		AtomicReference<String> sentCode = new AtomicReference<>();
		verification = verificationService(new RedisEmailVerificationAdapter(redis), userAccounts, sentCode);
		verification.sendEmailCode(new SendEmailVerificationCommand(email));
		return verification.verifyEmailCode(new VerifyEmailCodeCommand(email, sentCode.get())).emailVerificationToken();
	}

	private EmailVerificationService verificationService(EmailVerificationStorePort store,
			CheckUserUniquenessPort uniqueness) {
		return verificationService(store, uniqueness, new AtomicReference<>());
	}

	private EmailVerificationService verificationService(EmailVerificationStorePort store,
			CheckUserUniquenessPort uniqueness, AtomicReference<String> sentCode) {
		EmailVerificationPolicyPort limits = new EmailVerificationPolicyPort() {
			public int maxCodeAttempts() { return 5; }
			public int resendSeconds() { return 60; }
			public int emailHourlyLimit() { return 5; }
		};
		IssueVerificationEmailPort sender = new IssueVerificationEmailPort() {
			public String issueVerificationEmail(String address) { throw new AssertionError("Password reset is outside this flow"); }
			public void deliverVerificationCode(String address, String code) { sentCode.set(code); }
		};
		QueryAllowedEmailUseCase allowed = new QueryAllowedEmailUseCase() {
			public boolean isEmailAllowed(String address) { return true; }
			public List<String> queryAllowedEmailDomains() { return List.of(); }
		};
		return new EmailVerificationService(store, limits, sender, allowed, uniqueness);
	}

	private AuthService authService(EmailVerificationStorePort store, CheckUserUniquenessPort uniqueness,
			SignUpTransactionPort transaction, EmailVerificationOperationsAlertPort alerts) {
		PasswordProtectionPort password = new PasswordProtectionPort() {
			public String protect(String raw) { return "password-hash"; }
			public boolean matches(String raw, String encoded) { return false; }
		};
		CheckSignUpCharacterSelectionPort character = id -> true;
		return new AuthService(mock(LoadUserForPasswordResetPort.class), uniqueness, userPersistence,
				mock(LoadVerificationPort.class), mock(ManageVerificationPort.class),
				mock(LoadCompletedEmailVerificationPort.class), mock(RecordCompletedEmailVerificationPort.class),
				mock(IssueVerificationEmailPort.class), password, character, store, alerts,
				transaction, new EmailVerificationPolicy(), new PasswordPolicy());
	}

	private static EmailVerificationOperationsAlertPort noopAlerts() {
		return errorType -> {};
	}

	private static EmailVerificationStorePort delegateCleanupFailure(EmailVerificationStorePort delegate) {
		return new EmailVerificationStorePort() {
			public EmailVerificationReservation prepare(String emailKey, String generation, String codeHash, int limit, int resend) {
				return delegate.prepare(emailKey, generation, codeHash, limit, resend);
			}
			public boolean activate(String emailKey, String generation, String codeHash) {
				return delegate.activate(emailKey, generation, codeHash);
			}
			public EmailVerificationAttempt verify(String emailKey, String codeHash, String tokenHash, String rawToken, int attempts) {
				return delegate.verify(emailKey, codeHash, tokenHash, rawToken, attempts);
			}
			public boolean isTokenValid(String emailKey, String tokenHash) {
				return delegate.isTokenValid(emailKey, tokenHash);
			}
			public boolean removeToken(String emailKey, String tokenHash) {
				throw new IllegalStateException("test failure");
			}
		};
	}

	private static SignUpCommand command(String email, String nickname, String token) {
		return new SignUpCommand(email, "abc@123", nickname, 1L, token);
	}
}
