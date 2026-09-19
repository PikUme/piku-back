package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.exception.SignupProfileException;
import com.pikume.back.user.application.exception.SignupProfileFailure;
import com.pikume.back.user.application.port.out.ResolveFixedCharacterAvatarPort;
import com.pikume.back.user.application.service.UserProfileCommandService;
import com.pikume.back.user.auth.application.dto.ResetPasswordCommand;
import com.pikume.back.user.auth.application.port.out.LoadCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.RecordCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.service.AuthService;
import com.pikume.back.user.auth.domain.VerifiedEmail;
import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.service.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:signup-profile-concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserProfileCommandService.class, UserAccountPersistenceAdapter.class, UserPersistenceAdapter.class,
	NicknameHoldPersistenceAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SignupProfilePersistenceIntegrationTest {
	@Autowired UserProfileCommandService service;
	@Autowired UserJpaRepository users;
	@Autowired JdbcTemplate jdbc;
	@Autowired UserAccountPersistenceAdapter accountLoads;
	@Autowired PlatformTransactionManager transactionManager;
	@MockitoBean ResolveFixedCharacterAvatarPort characters;
	@MockitoSpyBean UserPersistenceAdapter accounts;

	@BeforeEach void setup() {
		jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_write_mutex (id INT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_holds (nickname VARCHAR(255) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, expires_at TIMESTAMP(6) NOT NULL)");
		jdbc.update("MERGE INTO nickname_write_mutex (id) KEY(id) VALUES (1)");
		jdbc.update("DELETE FROM nickname_holds");
		users.deleteAll();
	}

	@Test void completedProfileUsesTheSameNormalizedDatabaseHoldForReservationAndUpdate() {
		User user=users.saveAndFlush(new User("profile@example.com","hash","현재닉",1L));
		assertThat(service.reserveIfAvailable("  새닉　 ",user.getId())).isTrue();
		var result=service.updateProfile(new com.pikume.back.user.application.dto.UpdateProfileCommand(user.getId(),"\t새닉\n",null));
		assertThat(result.success()).isTrue();assertThat(result.newNickname()).isEqualTo("새닉");
		assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,user.getId())).isEqualTo("새닉");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds",Integer.class)).isZero();
	}

	@Test void normalizedSignupHoldRejectsAnotherMemberAndSupportsCompletionRetry() {
		User first=users.saveAndFlush(User.pending("first@example.com",null,"가입대기_first",1L));
		User second=users.saveAndFlush(User.pending("second@example.com",null,"가입대기_second",1L));
		var reserved=service.reserveSignupNickname(first.getId(),"  완료닉　 ");
		assertThat(reserved.nickname()).isEqualTo("완료닉");
		assertThat(jdbc.queryForObject("SELECT nickname FROM nickname_holds",String.class)).isEqualTo("완료닉");
		assertThatThrownBy(()->service.reserveSignupNickname(second.getId(),"\t완료닉\n"))
			.isInstanceOfSatisfying(SignupProfileException.class,e->assertThat(e.getFailure()).isEqualTo(SignupProfileFailure.NICKNAME_UNAVAILABLE));
		assertThat(service.reserveSignupNickname(first.getId(),"완료닉").expiresAt()).isEqualTo(reserved.expiresAt());
		given(characters.resolveFixedCharacterObjectKey(1L)).willReturn(Optional.of("default.webp"));
		var completed=service.completeSignupProfile(first.getId(),"\t완료닉\n",1L);
		assertThat(service.completeSignupProfile(first.getId()," 완료닉 ",1L)).isEqualTo(completed);
		assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,first.getId())).isEqualTo("완료닉");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds",Integer.class)).isZero();
	}

	@Test void failedCharacterSelectionKeepsHoldAndPendingAccountThenRetryCompletes() {
		User user = users.saveAndFlush(User.pending("user@example.com", null, "가입대기_123", 1L));
		var reservation = service.reserveSignupNickname(user.getId(), "final");

		assertThatThrownBy(() -> service.completeSignupProfile(user.getId(), "final", 9L))
			.isInstanceOfSatisfying(SignupProfileException.class,
				exception -> assertThat(exception.getFailure()).isEqualTo(SignupProfileFailure.INVALID_CHARACTER));
		assertThat(users.findById(user.getId()).orElseThrow().isProfileSetupRequired()).isTrue();
		assertThat(service.reserveSignupNickname(user.getId(), "final").expiresAt()).isEqualTo(reservation.expiresAt());

		given(characters.resolveFixedCharacterObjectKey(1L)).willReturn(Optional.of("default.webp"));
		var result = service.completeSignupProfile(user.getId(), "final", 1L);
		assertThat(result.profileSetupStatus()).isEqualTo("COMPLETED");
		assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("final");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds", Integer.class)).isZero();
	}

	@Test void persistenceFailureRollsBackProfileAndPreservesReservation() {
		User user = users.saveAndFlush(User.pending("rollback@example.com", null, "가입대기_rollback", 1L));
		service.reserveSignupNickname(user.getId(), "final");
		given(characters.resolveFixedCharacterObjectKey(1L)).willReturn(Optional.of("default.webp"));
		doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("storage failure"); })
			.when(accounts).recordUserAccount(any());

		assertThatThrownBy(() -> service.completeSignupProfile(user.getId(), "final", 1L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(users.findById(user.getId()).orElseThrow().isProfileSetupRequired()).isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds WHERE user_id = ?", Integer.class, user.getId())).isEqualTo(1);
	}

	@Test void simultaneousIdenticalCompletionCommitsOnceAndBothRequestsSucceed() throws Exception {
		User user = users.saveAndFlush(User.pending("concurrent@example.com", null, "가입대기_concurrent", 1L));
		service.reserveSignupNickname(user.getId(), "final");
		given(characters.resolveFixedCharacterObjectKey(1L)).willReturn(Optional.of("default.webp"));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<String>> results = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
				return service.completeSignupProfile(user.getId(), "final", 1L).profileSetupStatus();
			})).toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<String> result : results) assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("COMPLETED");
			verify(accounts, times(1)).recordUserAccount(any());
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds", Integer.class)).isZero();
		} finally { executor.shutdownNow(); }
	}

	@ParameterizedTest(name = "비밀번호 재설정과 가입 상태 변경 경합: 탈퇴={0}")
	@ValueSource(booleans = {false, true})
	void passwordResetPreservesConcurrentSignupCompletionOrWithdrawal(boolean withdraw) throws Exception {
		User user = users.saveAndFlush(User.pending("reset@example.com", "old-hash", "가입대기_reset", 1L));
		if (!withdraw) {
			service.reserveSignupNickname(user.getId(), "final");
			given(characters.resolveFixedCharacterObjectKey(2L)).willReturn(Optional.of("selected.webp"));
		}
		CountDownLatch resetHasReadUser = new CountDownLatch(1);
		CountDownLatch resumeReset = new CountDownLatch(1);
		PasswordProtectionPort passwords = mock(PasswordProtectionPort.class);
		given(passwords.protect("NewPassword!1")).willAnswer(invocation -> {
			resetHasReadUser.countDown();
			if (!resumeReset.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("reset barrier timeout");
			return "new-hash";
		});
		LoadCompletedEmailVerificationPort verified = mock(LoadCompletedEmailVerificationPort.class);
		given(verified.loadLatestVerification(user.getEmail(), VerificationType.PASSWORD_RESET))
			.willReturn(Optional.of(new VerifiedEmail(user.getEmail(), VerificationType.PASSWORD_RESET)));
		AuthService auth = new AuthService(accountLoads, accountLoads, accounts, null, null, verified,
			mock(RecordCompletedEmailVerificationPort.class), null, passwords, null, null,
			new EmailVerificationPolicy(), new PasswordPolicy(), null);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> reset = executor.submit(() -> transaction.executeWithoutResult(status ->
				auth.resetPassword(new ResetPasswordCommand(user.getEmail(), "NewPassword!1"))));
			assertThat(resetHasReadUser.await(5, TimeUnit.SECONDS)).isTrue();
			Future<?> profileChange = executor.submit(() -> {
				if (withdraw) service.withdrawPendingSignup(user.getId());
				else service.completeSignupProfile(user.getId(), "final", 2L);
			});
			try {
				profileChange.get(1, TimeUnit.SECONDS);
			} catch (TimeoutException waitingForResetLock) {
				// The corrected reader holds the user lock until the reset commits.
			}
			resumeReset.countDown();
			reset.get(10, TimeUnit.SECONDS);
			profileChange.get(10, TimeUnit.SECONDS);

			User saved = users.findById(user.getId()).orElseThrow();
			assertThat(saved.getPassword()).isEqualTo("new-hash");
			if (withdraw) assertThat(saved.isWithdrawn()).isTrue();
			else {
				assertThat(saved.isProfileSetupRequired()).isFalse();
				assertThat(saved.getNickname()).isEqualTo("final");
				assertThat(saved.getCharacterId()).isEqualTo(2L);
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds", Integer.class)).isZero();
		} finally {
			resumeReset.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}
}
