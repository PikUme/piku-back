package com.pikume.back.user.auth.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.auth.application.dto.ResetPasswordCommand;
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCommand;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.VerifiedEmail;
import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import com.pikume.back.user.domain.exception.InvalidNicknameException;
import com.pikume.back.user.domain.service.PasswordPolicy;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

	@InjectMocks
	private AuthService authService;

	@Mock
	private LoadUserForPasswordResetPort loadUserForPasswordResetPort;
	@Mock
	private CheckUserUniquenessPort checkUserUniquenessPort;
	@Mock
	private RecordUserAccountPort recordUserAccountPort;
	@Mock
	private LoadVerificationPort loadVerificationPort;
	@Mock
	private ManageVerificationPort manageVerificationPort;
	@Mock
	private LoadCompletedEmailVerificationPort loadCompletedEmailVerificationPort;
	@Mock
	private RecordCompletedEmailVerificationPort recordCompletedEmailVerificationPort;
	@Mock
	private IssueVerificationEmailPort issueVerificationEmailPort;
	@Mock
	private PasswordProtectionPort passwordProtectionPort;
	@Mock
	private CheckSignUpCharacterSelectionPort checkSignUpCharacterSelectionPort;
	@Mock
	private QueryAllowedEmailUseCase queryAllowedEmailUseCase;
	@Spy
	private EmailVerificationPolicy emailVerificationPolicy = new EmailVerificationPolicy();
	@Spy
	private PasswordPolicy passwordPolicy = new PasswordPolicy();

	@Nested
	@DisplayName("signup")
	class Signup {

		@Test
		@DisplayName("유효하지 않은 닉네임은 다른 Port를 호출하기 전에 거절한다")
		void rejectsInvalidNicknameBeforeCallingPorts() {
			SignUpCommand command = new SignUpCommand("test@piku.store", "abc@123", " \u2003\u3000 ", 1L);

			assertThatThrownBy(() -> authService.signUp(command))
					.isInstanceOf(InvalidNicknameException.class);

			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(loadCompletedEmailVerificationPort).shouldHaveNoInteractions();
			then(recordUserAccountPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("잘못된 이메일 형식을 계정 오류로 변환하고 Port를 호출하지 않는다")
		void rejectsInvalidEmailBeforeCallingPorts() {
			SignUpCommand command = new SignUpCommand("not-an-email", "abc@123", "테스트", 1L);

			assertThatThrownBy(() -> authService.signUp(command))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_EMAIL));

			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(passwordProtectionPort).shouldHaveNoInteractions();
			then(recordUserAccountPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("잘못된 비밀번호 형식을 계정 오류로 변환하고 Port를 호출하지 않는다")
		void rejectsInvalidPasswordBeforeCallingPorts() {
			SignUpCommand command = new SignUpCommand("test@piku.store", "plainPassword", "테스트", 1L);

			assertThatThrownBy(() -> authService.signUp(command))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode())
									.isEqualTo(AuthErrorCode.INVALID_PASSWORD));

			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(passwordProtectionPort).shouldHaveNoInteractions();
			then(recordUserAccountPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("유효한 요청으로 회원가입에 성공한다")
		void signupSuccess() throws Exception {
			SignUpCommand dto = new SignUpCommand("test@piku.store", "abc@123", " \u2003테스트\u3000 ", 1L);

			given(checkUserUniquenessPort.isEmailRegistered("test@piku.store")).willReturn(false);

			VerifiedEmail verified = new VerifiedEmail("test@piku.store", VerificationType.SIGN_UP);
			Field idField = VerifiedEmail.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(verified, 1L);

			given(
					loadCompletedEmailVerificationPort.loadLatestVerification("test@piku.store", VerificationType.SIGN_UP))
					.willReturn(Optional.of(verified));
			given(passwordProtectionPort.protect("abc@123")).willReturn("encodedPw");
			given(checkSignUpCharacterSelectionPort.isSelectableFixedCharacter(1L)).willReturn(true);
			given(recordUserAccountPort.recordUserAccount(any(User.class))).willReturn(null);

			authService.signUp(dto);

			then(recordUserAccountPort).should().recordUserAccount(argThat(user ->
					Long.valueOf(1L).equals(user.getCharacterId()) && "테스트".equals(user.getNickname())));
			then(recordCompletedEmailVerificationPort).should().recordCompletedVerification(verified);
		}

		@Test
		@DisplayName("존재하지 않는 고정 캐릭터로 회원가입 시 예외가 발생하고 저장하지 않는다")
		void signupFailFixedCharacterNotFound() throws Exception {
			SignUpCommand dto = new SignUpCommand("test@piku.store", "abc@123", "테스트", 999L);
			given(checkUserUniquenessPort.isEmailRegistered("test@piku.store")).willReturn(false);

			VerifiedEmail verified = new VerifiedEmail("test@piku.store", VerificationType.SIGN_UP);
			Field idField = VerifiedEmail.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(verified, 1L);

			given(
					loadCompletedEmailVerificationPort.loadLatestVerification("test@piku.store", VerificationType.SIGN_UP))
					.willReturn(Optional.of(verified));
			given(checkSignUpCharacterSelectionPort.isSelectableFixedCharacter(999L)).willReturn(false);

			assertThatThrownBy(() -> authService.signUp(dto))
					.isInstanceOfSatisfying(AuthException.class,
							ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.FIXED_CHARACTER_NOT_FOUND));

			then(recordCompletedEmailVerificationPort).should(never()).recordCompletedVerification(any());
			then(recordUserAccountPort).should(never()).recordUserAccount(any());
		}

		@Test
		@DisplayName("이미 존재하는 이메일로 회원가입 시 예외가 발생한다")
		void signupFailDuplicateEmail() {
			SignUpCommand dto = new SignUpCommand("dup@piku.store", "abc@123", "테스트", 1L);
			given(checkUserUniquenessPort.isEmailRegistered("dup@piku.store")).willReturn(true);

			assertThatThrownBy(() -> authService.signUp(dto))
					.isInstanceOf(AuthException.class);

			then(recordUserAccountPort).should(never()).recordUserAccount(any());
		}

		@Test
		@DisplayName("회원가입 저장 경쟁의 이메일 충돌을 계정 오류로 변환한다")
		void signupTranslatesEmailConflictFromPersistence() {
			SignUpCommand command = new SignUpCommand("race@piku.store", "abc@123", "테스트", 1L);
			VerifiedEmail verified = new VerifiedEmail("race@piku.store", VerificationType.SIGN_UP);
			given(checkUserUniquenessPort.isEmailRegistered("race@piku.store")).willReturn(false);
			given(loadCompletedEmailVerificationPort.loadLatestVerification(
					"race@piku.store", VerificationType.SIGN_UP)).willReturn(Optional.of(verified));
			given(checkSignUpCharacterSelectionPort.isSelectableFixedCharacter(1L)).willReturn(true);
			given(passwordProtectionPort.protect("abc@123")).willReturn("encodedPw");
			given(recordUserAccountPort.recordUserAccount(any(User.class))).willThrow(new EmailAlreadyExistsException());

			assertThatThrownBy(() -> authService.signUp(command))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode())
									.isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS));
		}

		@Test
		@DisplayName("이메일 인증이 없으면 회원가입 시 예외가 발생한다")
		void signupFailNoVerification() {
			SignUpCommand dto = new SignUpCommand("test@piku.store", "abc@123", "테스트", 1L);
			given(checkUserUniquenessPort.isEmailRegistered("test@piku.store")).willReturn(false);
			given(
					loadCompletedEmailVerificationPort.loadLatestVerification("test@piku.store", VerificationType.SIGN_UP))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.signUp(dto))
					.isInstanceOf(AuthException.class);
		}
	}

	@Nested
	@DisplayName("sendSignUpVerificationEmail")
	class SendSignUpVerification {

		@Test
		@DisplayName("잘못된 이메일 형식을 계정 오류로 변환하고 발송하지 않는다")
		void rejectsInvalidEmailBeforeSending() {
			assertThatThrownBy(() -> authService.sendSignUpVerificationEmail("not-an-email"))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_EMAIL));

			then(queryAllowedEmailUseCase).shouldHaveNoInteractions();
			then(issueVerificationEmailPort).shouldHaveNoInteractions();
			then(manageVerificationPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("인증 이메일 발송에 성공한다")
		void sendSuccess() {
			given(queryAllowedEmailUseCase.isEmailAllowed("test@piku.store")).willReturn(true);
			given(issueVerificationEmailPort.issueVerificationEmail("test@piku.store")).willReturn("123456");

			authService.sendSignUpVerificationEmail("test@piku.store");

			then(manageVerificationPort).should().storeVerification(any(Verification.class));
		}
	}

	@Nested
	@DisplayName("sendPasswordResetVerificationEmail")
	class SendPasswordResetVerification {

		@Test
		@DisplayName("잘못된 이메일 형식을 계정 오류로 변환하고 발송하지 않는다")
		void rejectsInvalidEmailBeforeSending() {
			assertThatThrownBy(() -> authService.sendPasswordResetVerificationEmail("not-an-email"))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_EMAIL));

			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(issueVerificationEmailPort).shouldHaveNoInteractions();
			then(manageVerificationPort).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("verifyCode")
	class VerifyCode {

		@Test
		@DisplayName("잘못된 이메일 형식을 계정 오류로 변환하고 인증 기록을 조회하지 않는다")
		void rejectsInvalidEmailBeforeLoadingVerification() {
			VerifyEmailCommand command =
					new VerifyEmailCommand("not-an-email", "123456", VerificationType.SIGN_UP);

			assertThatThrownBy(() -> authService.verifyCode(command))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_EMAIL));

			then(loadVerificationPort).shouldHaveNoInteractions();
			then(manageVerificationPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("유효한 인증 코드 검증에 성공한다")
		void verifySuccess() throws Exception {
			VerifyEmailCommand dto = new VerifyEmailCommand("test@piku.store", "123456", VerificationType.SIGN_UP);
			Verification v = new Verification("test@piku.store", "123456", VerificationType.SIGN_UP,
					LocalDateTime.now().plusMinutes(5));
			Field idField = Verification.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(v, 1L);

			given(loadVerificationPort.loadVerification("test@piku.store", VerificationType.SIGN_UP))
					.willReturn(Optional.of(v));

			authService.verifyCode(dto);

			then(manageVerificationPort).should().removeVerification(v);
			then(recordCompletedEmailVerificationPort).should().recordCompletedVerification(any(VerifiedEmail.class));
		}

		@Test
		@DisplayName("만료된 인증 코드로 검증 시 예외가 발생한다")
		void verifyFailExpired() throws Exception {
			VerifyEmailCommand dto = new VerifyEmailCommand("test@piku.store", "123456", VerificationType.SIGN_UP);
			Verification v = new Verification("test@piku.store", "123456", VerificationType.SIGN_UP,
					LocalDateTime.now().plusMinutes(5));

			Field expiresField = Verification.class.getDeclaredField("expiresAt");
			expiresField.setAccessible(true);
			expiresField.set(v, LocalDateTime.now().minusMinutes(10));

			Field idField = Verification.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(v, 1L);

			given(loadVerificationPort.loadVerification("test@piku.store", VerificationType.SIGN_UP))
					.willReturn(Optional.of(v));

			assertThatThrownBy(() -> authService.verifyCode(dto))
					.isInstanceOf(AuthException.class);
		}

		@Test
		@DisplayName("불일치 인증 코드로 검증 시 예외가 발생한다")
		void verifyFailMismatch() throws Exception {
			VerifyEmailCommand dto = new VerifyEmailCommand("test@piku.store", "999999", VerificationType.SIGN_UP);
			Verification v = new Verification("test@piku.store", "123456", VerificationType.SIGN_UP,
					LocalDateTime.now().plusMinutes(5));
			Field idField = Verification.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(v, 1L);

			given(loadVerificationPort.loadVerification("test@piku.store", VerificationType.SIGN_UP))
					.willReturn(Optional.of(v));

			assertThatThrownBy(() -> authService.verifyCode(dto))
					.isInstanceOf(AuthException.class);
		}
	}

	@Nested
	@DisplayName("resetPassword")
	class ResetPassword {

		@Test
		@DisplayName("잘못된 이메일 형식을 계정 오류로 변환하고 사용자를 조회하지 않는다")
		void rejectsInvalidEmailBeforeLoadingUser() {
			ResetPasswordCommand command = new ResetPasswordCommand("not-an-email", "newPwd@1");

			assertThatThrownBy(() -> authService.resetPassword(command))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_EMAIL));

			then(loadUserForPasswordResetPort).shouldHaveNoInteractions();
			then(passwordProtectionPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("잘못된 비밀번호 형식을 계정 오류로 변환하고 사용자를 조회하지 않는다")
		void rejectsInvalidPasswordBeforeLoadingUser() {
			ResetPasswordCommand command = new ResetPasswordCommand("test@piku.store", "plainPassword");

			assertThatThrownBy(() -> authService.resetPassword(command))
					.isInstanceOfSatisfying(AuthException.class,
							exception -> assertThat(exception.getErrorCode())
									.isEqualTo(AuthErrorCode.INVALID_PASSWORD));

			then(loadUserForPasswordResetPort).shouldHaveNoInteractions();
			then(passwordProtectionPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("비밀번호 재설정에 성공한다")
		void resetSuccess() throws Exception {
			ResetPasswordCommand dto = new ResetPasswordCommand("test@piku.store", "newPwd@1");
			User user = new User("test@piku.store", "oldPw", "nick", 1L);
			given(loadUserForPasswordResetPort.loadPasswordResetUser("test@piku.store")).willReturn(Optional.of(user));

			VerifiedEmail verified = new VerifiedEmail("test@piku.store", VerificationType.PASSWORD_RESET);
			Field idField = VerifiedEmail.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(verified, 1L);
			given(loadCompletedEmailVerificationPort.loadLatestVerification("test@piku.store",
					VerificationType.PASSWORD_RESET))
					.willReturn(Optional.of(verified));
			given(passwordProtectionPort.protect("newPwd@1")).willReturn("encodedNew");

			authService.resetPassword(dto);

			then(recordCompletedEmailVerificationPort).should().recordCompletedVerification(verified);
		}

		@Test
		@DisplayName("존재하지 않는 사용자로 비밀번호 재설정 시 예외가 발생한다")
		void resetFailUserNotFound() {
			ResetPasswordCommand dto = new ResetPasswordCommand("unknown@piku.store", "newPwd@1");
			given(loadUserForPasswordResetPort.loadPasswordResetUser("unknown@piku.store")).willReturn(Optional.empty());

			assertThatThrownBy(() -> authService.resetPassword(dto))
					.isInstanceOf(AuthException.class);
		}
	}
}
