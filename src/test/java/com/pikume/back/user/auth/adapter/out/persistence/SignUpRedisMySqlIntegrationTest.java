package com.pikume.back.user.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pikume.back.user.adapter.out.persistence.UserAccountPersistenceAdapter;
import com.pikume.back.user.adapter.out.persistence.UserJpaRepository;
import com.pikume.back.user.adapter.out.persistence.UserPersistenceAdapter;
import com.pikume.back.user.adapter.out.persistence.MySqlNicknameIdentityAdapter;
import com.pikume.back.user.adapter.out.persistence.MySqlNicknameWriteTransactionAdapter;
import com.pikume.back.user.adapter.out.cache.RedisNicknameReservationAdapter;
import com.pikume.back.user.adapter.out.cache.RedisNicknameHoldAdapter;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.application.port.out.NicknameReservationStorePort;
import com.pikume.back.user.application.port.out.NicknameHoldPort;
import com.pikume.back.user.application.service.UserProfileCommandService;
import com.pikume.back.user.application.port.out.ResolveFixedCharacterAvatarPort;
import com.pikume.back.user.domain.vo.Nickname;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.auth.adapter.out.cache.RedisEmailVerificationAdapter;
import com.pikume.back.user.auth.application.exception.AuthException;
import com.pikume.back.user.auth.application.port.out.CheckSignUpCharacterSelectionPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationOperationsAlertPort;
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
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import com.pikume.back.user.application.dto.NicknameReservationResult;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.service.PasswordPolicy;
import com.pikume.back.user.domain.vo.Email;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Testcontainers
@Import({UserPersistenceAdapter.class, UserAccountPersistenceAdapter.class, SignUpTransactionAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SignUpRedisMySqlIntegrationTest {

	private static final String SIGNUP_TOKEN = "integration-signup-token";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("email_signup_flow_test")
			.withUsername("test")
			.withPassword("test");
	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

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
		if (redisConnection != null) {
			redisConnection.destroy();
		}
	}

	@Autowired
	private UserJpaRepository users;
	@Autowired
	private UserAccountPersistenceAdapter accounts;
	@Autowired
	private UserPersistenceAdapter userPersistence;
	@Autowired
	private SignUpTransactionPort transactions;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void clearDatabase() {
		users.deleteAllInBatch();
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS nickname_write_mutex (id TINYINT NOT NULL PRIMARY KEY)");
		jdbcTemplate.update("INSERT IGNORE INTO nickname_write_mutex (id) VALUES (1)");
	}

	@AfterEach
	void clearRedis() {
		redis.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void preservesNicknameConflictThroughTheMySqlWriteTransaction() {
		MySqlNicknameWriteTransactionAdapter writeTransaction =
				new MySqlNicknameWriteTransactionAdapter(jdbcTemplate, transactionManager);

		assertThatThrownBy(() -> writeTransaction.execute(() -> {
			throw new NicknameReservationConflictException();
		})).isInstanceOf(NicknameReservationConflictException.class);
	}

	@Test
	void actualRedisProofIsConsumedOnlyAfterMysqlSignupCommit() {
		String email = "signup-flow@example.com";
		RedisEmailVerificationAdapter store = new RedisEmailVerificationAdapter(redis);
		verifyCode(store, email, "123456");
		AuthService signup = authService(store, mock(EmailVerificationOperationsAlertPort.class));

		signup.signUp(command(email, "signup-flow-nickname"));

		assertThat(users.findByEmail(new Email(email))).isPresent();
		assertThat(store.loadProof(EmailVerificationService.hash(email), EmailVerificationService.hash(SIGNUP_TOKEN)))
				.isEmpty();
	}

	@Test
	void signupWithAnotherNicknameCommitsThenConditionallyClearsSignupOwnersCapturedHold() {
		String email = "replace-hold@example.com";
		RedisEmailVerificationAdapter proofStore = new RedisEmailVerificationAdapter(redis);
		verifyCode(proofStore, email, "123456");
		NicknameReservationStorePort delegate = new RedisNicknameReservationAdapter(redis);
		String owner = "signup:" + EmailVerificationService.hash(email);
		NicknameReservationResult existing = delegate.reserve(owner,
				new MySqlNicknameIdentityAdapter(jdbcTemplate).keyFor(new Nickname("reserved-a")), "Reserved A");
		AtomicBoolean accountVisibleAtCleanup = new AtomicBoolean();
		AtomicBoolean cleanupWasOutsideTransaction = new AtomicBoolean();
		AtomicBoolean firstCleanupObserved = new AtomicBoolean();
		NicknameReservationStorePort reservations = new ObservedReservationStore(delegate,
				() -> {
					if (firstCleanupObserved.compareAndSet(false, true)) {
						accountVisibleAtCleanup.set(users.findByEmail(new Email(email)).isPresent());
						cleanupWasOutsideTransaction.set(!org.springframework.transaction.support.TransactionSynchronizationManager
								.isActualTransactionActive());
					}
				});

		authService(proofStore, mock(EmailVerificationOperationsAlertPort.class), transactions, reservations)
				.signUp(command(email, "signup-b"));

		assertThat(users.findByEmail(new Email(email))).isPresent();
		assertThat(reservations.load(owner)).isEmpty();
		assertThat(accountVisibleAtCleanup).isTrue();
		assertThat(cleanupWasOutsideTransaction).isTrue();
		assertThat(delegate.releaseIfVersionMatches(owner, existing.nicknameKey(), existing.version())).isFalse();
	}

	@Test
	void failedSignupAfterMysqlInsertRollsBackAndPreservesActualRedisHoldAndProof() {
		String email = "rollback-hold@example.com";
		RedisEmailVerificationAdapter proofStore = new RedisEmailVerificationAdapter(redis);
		verifyCode(proofStore, email, "123456");
		NicknameReservationStorePort reservations = new RedisNicknameReservationAdapter(redis);
		String owner = "signup:" + EmailVerificationService.hash(email);
		NicknameReservationResult existing = reservations.reserve(owner, "hold-key", "Hold A");
		SignUpTransactionPort insertThenFail = user -> {
			transactions.register(user);
			throw new IllegalStateException("forced failure after MySQL insert");
		};

		assertThatThrownBy(() -> authService(proofStore, mock(EmailVerificationOperationsAlertPort.class),
				insertThenFail, reservations).signUp(command(email, "rollback-name")))
				.isInstanceOf(IllegalStateException.class);

		assertThat(users.findByEmail(new Email(email))).isEmpty();
		assertThat(proofStore.loadProof(EmailVerificationService.hash(email), EmailVerificationService.hash(SIGNUP_TOKEN)))
				.isPresent();
		assertThat(reservations.load(owner)).contains(existing);
	}

	@Test
	void profileReservationAndSignupShareTheMySqlWriteMutexAroundRealRedis() throws Exception {
		String userId = transactions.register(
				new User("profile-race@example.com", "password-hash", "profile-before", 1L)).getId();
		String email = "signup-race@example.com";
		RedisEmailVerificationAdapter proofStore = new RedisEmailVerificationAdapter(redis);
		verifyCode(proofStore, email, "123456");
		NicknameReservationStorePort reservations = new RedisNicknameReservationAdapter(redis);
		NicknameHoldPort holds = new RedisNicknameHoldAdapter(reservations, new MySqlNicknameIdentityAdapter(jdbcTemplate));
		UserProfileCommandService profile = new UserProfileCommandService(
				accounts, userPersistence, accounts, mock(ResolveFixedCharacterAvatarPort.class), holds,
				new MySqlNicknameWriteTransactionAdapter(jdbcTemplate, transactionManager),
				new MySqlNicknameIdentityAdapter(jdbcTemplate));
		AuthService signup = authService(proofStore, mock(EmailVerificationOperationsAlertPort.class));
		CyclicBarrier start = new CyclicBarrier(2);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var profileAttempt = executor.submit(() -> {
				start.await();
				return profile.reserveIfAvailable("shared-race-name", userId);
			});
			var signupAttempt = executor.submit(() -> {
				start.await();
				try {
					signup.signUp(command(email, "shared-race-name"));
					return true;
				} catch (AuthException exception) {
					return false;
				}
			});
			boolean profileReserved = profileAttempt.get(15, TimeUnit.SECONDS);
			boolean accountCreated = signupAttempt.get(15, TimeUnit.SECONDS);

			assertThat(profileReserved).isNotEqualTo(accountCreated);
			assertThat(users.findByEmail(new Email(email)).isPresent()).isEqualTo(accountCreated);
			assertThat(reservations.load("user:" + userId).isPresent()).isEqualTo(profileReserved);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void databaseConflictRollsBackSignupAndLeavesActualRedisProofForRetry() {
		String email = "rollback@example.com";
		transactions.register(new User("existing@example.com", "password-hash", "taken-nickname", 1L));
		RedisEmailVerificationAdapter store = new RedisEmailVerificationAdapter(redis);
		verifyCode(store, email, "123456");
		AuthService signup = authService(store, mock(EmailVerificationOperationsAlertPort.class));

		assertThatThrownBy(() -> signup.signUp(command(email, "taken-nickname")))
				.isInstanceOf(AuthException.class);
		assertThat(users.findByEmail(new Email(email))).isEmpty();
		assertThat(store.loadProof(EmailVerificationService.hash(email), EmailVerificationService.hash(SIGNUP_TOKEN)))
				.isPresent();
	}

	@Test
	void concurrentSignupWithTheSameVerifiedEmailCreatesOneMysqlAccount() throws Exception {
		String email = "concurrent@example.com";
		RedisEmailVerificationAdapter delegate = new RedisEmailVerificationAdapter(redis);
		verifyCode(delegate, email, "123456");
		var executor = Executors.newFixedThreadPool(2);
		CyclicBarrier start = new CyclicBarrier(2);
		EmailVerificationStorePort bothReadTheProof = new DelegatingStore(delegate) {
			@Override
			public Optional<SignupEmailProof> loadProof(String emailKey, String tokenHash) {
				Optional<SignupEmailProof> proof = super.loadProof(emailKey, tokenHash);
				try {
					start.await(10, TimeUnit.SECONDS);
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
				return proof;
			}
		};
		AuthService signup = authService(bothReadTheProof, mock(EmailVerificationOperationsAlertPort.class));
		try {
			var first = executor.submit(() -> trySignup(signup, command(email, "concurrent-one"), start));
			var second = executor.submit(() -> trySignup(signup, command(email, "concurrent-two"), start));
			assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder("created", "email-conflict");
			assertThat(users.count()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void committedSignupSurvivesRedisCleanupFailureAndReportsItOperationally() {
		String email = "cleanup@example.com";
		RedisEmailVerificationAdapter delegate = new RedisEmailVerificationAdapter(redis);
		verifyCode(delegate, email, "123456");
		EmailVerificationStorePort failingCleanup = new DelegatingStore(delegate) {
			@Override
			public boolean removeProofIfVersionMatches(String emailKey, String version) {
				throw new IllegalStateException("Redis unavailable");
			}
		};
		EmailVerificationOperationsAlertPort alerts = mock(EmailVerificationOperationsAlertPort.class);

		authService(failingCleanup, alerts).signUp(command(email, "cleanup-nickname"));

		assertThat(users.findByEmail(new Email(email))).isPresent();
		assertThat(delegate.loadProof(EmailVerificationService.hash(email),
				EmailVerificationService.hash(SIGNUP_TOKEN))).isPresent();
		verify(alerts).signupProofCleanupFailed("IllegalStateException");
	}

	private AuthService authService(EmailVerificationStorePort store, EmailVerificationOperationsAlertPort alerts) {
		return authService(store, alerts, transactions, new RedisNicknameReservationAdapter(redis));
	}

	private AuthService authService(EmailVerificationStorePort store, EmailVerificationOperationsAlertPort alerts,
			SignUpTransactionPort signupTransactions, NicknameReservationStorePort reservations) {
		CheckSignUpCharacterSelectionPort characters = mock(CheckSignUpCharacterSelectionPort.class);
		given(characters.isSelectableFixedCharacter(1L)).willReturn(true);
		PasswordProtectionPort passwords = mock(PasswordProtectionPort.class);
		given(passwords.protect("abc@123")).willReturn("password-hash");
		return new AuthService(
				mock(LoadUserForPasswordResetPort.class),
				accounts,
				userPersistence,
				mock(LoadVerificationPort.class),
				mock(ManageVerificationPort.class),
				mock(LoadCompletedEmailVerificationPort.class),
				mock(RecordCompletedEmailVerificationPort.class),
				mock(IssueVerificationEmailPort.class),
				passwords,
				characters,
				new EmailVerificationPolicy(),
				new PasswordPolicy(),
				store,
				alerts,
				signupTransactions,
				new MySqlNicknameWriteTransactionAdapter(jdbcTemplate, transactionManager),
				new MySqlNicknameIdentityAdapter(jdbcTemplate),
				reservations);
	}

	private static final class ObservedReservationStore implements NicknameReservationStorePort {
		private final NicknameReservationStorePort delegate;
		private final Runnable onRelease;

		private ObservedReservationStore(NicknameReservationStorePort delegate, Runnable onRelease) {
			this.delegate = delegate;
			this.onRelease = onRelease;
		}

		@Override
		public NicknameReservationResult reserve(String ownerKey, String nicknameKey, String nickname) {
			return delegate.reserve(ownerKey, nicknameKey, nickname);
		}

		@Override
		public Optional<NicknameReservationResult> load(String ownerKey) {
			return delegate.load(ownerKey);
		}

		@Override
		public boolean isHeldBy(String nicknameKey, String ownerKey) {
			return delegate.isHeldBy(nicknameKey, ownerKey);
		}

		@Override
		public boolean isReservedByOther(String nicknameKey, String ownerKey) {
			return delegate.isReservedByOther(nicknameKey, ownerKey);
		}

		@Override
		public boolean releaseIfVersionMatches(String ownerKey, String nicknameKey, String version) {
			onRelease.run();
			return delegate.releaseIfVersionMatches(ownerKey, nicknameKey, version);
		}
	}

	private static void verifyCode(EmailVerificationStorePort store, String email, String code) {
		String emailKey = EmailVerificationService.hash(email);
		String generation = "generation-" + email;
		store.reserve(emailKey, generation, EmailVerificationService.hash(code), UUID.randomUUID().toString());
		store.activate(emailKey, generation);
		assertThat(store.verify(emailKey, EmailVerificationService.hash(code), UUID.randomUUID().toString(),
				EmailVerificationService.hash(SIGNUP_TOKEN)).status())
				.isEqualTo(EmailVerificationStorePort.VerificationStatus.VERIFIED);
	}

	private static String trySignup(AuthService signup, SignUpCommand command, CyclicBarrier start) throws Exception {
		start.await();
		try {
			signup.signUp(command);
			return "created";
		} catch (AuthException exception) {
			return "email-conflict";
		}
	}

	private static SignUpCommand command(String email, String nickname) {
		return new SignUpCommand(email, "abc@123", nickname, 1L, SIGNUP_TOKEN);
	}

	private abstract static class DelegatingStore implements EmailVerificationStorePort {
		private final EmailVerificationStorePort delegate;

		private DelegatingStore(EmailVerificationStorePort delegate) {
			this.delegate = delegate;
		}

		@Override
		public ReservationResult reserve(String emailKey, String generation, String codeHash, String requestId) {
			return delegate.reserve(emailKey, generation, codeHash, requestId);
		}

		@Override
		public Optional<LocalDateTime> activate(String emailKey, String generation) {
			return delegate.activate(emailKey, generation);
		}

		@Override
		public VerificationResult verify(String emailKey, String submittedCodeHash, String version, String tokenHash) {
			return delegate.verify(emailKey, submittedCodeHash, version, tokenHash);
		}

		@Override
		public Optional<com.pikume.back.user.auth.application.dto.SignupEmailProof> loadProof(String emailKey,
				String tokenHash) {
			return delegate.loadProof(emailKey, tokenHash);
		}

		@Override
		public boolean removeProofIfVersionMatches(String emailKey, String version) {
			return delegate.removeProofIfVersionMatches(emailKey, version);
		}
	}
}
