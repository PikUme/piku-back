package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.auth.application.dto.ResetPasswordCommand;
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCommand;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.in.ResetPasswordUseCase;
import com.pikume.back.user.auth.application.port.in.SignUpUseCase;
import com.pikume.back.user.auth.application.port.in.VerifyEmailUseCase;
import com.pikume.back.user.auth.application.port.out.CheckSignUpCharacterSelectionPort;
import com.pikume.back.user.auth.application.port.out.LoadVerificationPort;
import com.pikume.back.user.auth.application.port.out.LoadCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.ManageVerificationPort;
import com.pikume.back.user.auth.application.port.out.RecordCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.VerifiedEmail;
import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import com.pikume.back.user.domain.exception.InvalidEmailException;
import com.pikume.back.user.domain.exception.InvalidPasswordException;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.service.PasswordPolicy;
import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService implements SignUpUseCase, VerifyEmailUseCase, ResetPasswordUseCase {

	private final LoadUserForPasswordResetPort loadUserForPasswordResetPort;
	private final CheckUserUniquenessPort checkUserUniquenessPort;
	private final RecordUserAccountPort recordUserAccountPort;
	private final LoadVerificationPort loadVerificationPort;
	private final ManageVerificationPort manageVerificationPort;
	private final LoadCompletedEmailVerificationPort loadCompletedEmailVerificationPort;
	private final RecordCompletedEmailVerificationPort recordCompletedEmailVerificationPort;
	private final IssueVerificationEmailPort issueVerificationEmailPort;
	private final PasswordProtectionPort passwordProtectionPort;
	private final CheckSignUpCharacterSelectionPort checkSignUpCharacterSelectionPort;
	private final QueryAllowedEmailUseCase queryAllowedEmailUseCase;
	private final EmailVerificationPolicy emailVerificationPolicy;
	private final PasswordPolicy passwordPolicy;

	@Override
	@Transactional
	public void signUp(SignUpCommand command) {
		Nickname nickname = new Nickname(command.nickname());
		requireValidEmail(command.email());
		requireValidPassword(command.password());
		if (checkUserUniquenessPort.isEmailRegistered(command.email())) {
			throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
		}

		VerifiedEmail verified = getValidVerifiedEmail(command.email(), VerificationType.SIGN_UP);
		requireSelectableFixedCharacter(command.fixedCharacterId());
		User user = new User(
				command.email(),
				passwordProtectionPort.protect(command.password()),
				nickname,
				command.fixedCharacterId());

		verified.markUsed();
		recordCompletedEmailVerificationPort.recordCompletedVerification(verified);
		try {
			recordUserAccountPort.recordUserAccount(user);
		} catch (EmailAlreadyExistsException exception) {
			throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
		} catch (NicknameAlreadyExistsException exception) {
			throw new AuthException(AuthErrorCode.NICKNAME_ALREADY_EXISTS);
		}
		log.info("event=user_signup outcome=success userId={}", user.getId());
	}

	@Override
	@Transactional
	public void sendSignUpVerificationEmail(String email) {
		requireValidEmail(email);
		if (!queryAllowedEmailUseCase.isEmailAllowed(email)) {
			throw new AuthException(AuthErrorCode.INVALID_EMAIL);
		}
		if (checkUserUniquenessPort.isEmailRegistered(email)) {
			log.info("event=verification_request outcome=accepted reason=email_already_registered");
		}
		saveVerificationCode(email, issueVerificationEmailPort.issueVerificationEmail(email), VerificationType.SIGN_UP);
	}

	@Override
	@Transactional
	public void sendPasswordResetVerificationEmail(String email) {
		requireValidEmail(email);
		if (!checkUserUniquenessPort.isEmailRegistered(email)) {
			log.info("event=password_reset_verification outcome=accepted reason=email_not_registered");
		}
		saveVerificationCode(
				email,
				issueVerificationEmailPort.issueVerificationEmail(email),
				VerificationType.PASSWORD_RESET);
	}

	@Override
	@Transactional
	public void verifyCode(VerifyEmailCommand command) {
		requireValidEmail(command.email());
		Verification verification = loadVerificationPort.loadVerification(command.email(), command.type())
				.orElseThrow(() -> new AuthException(AuthErrorCode.VERIFICATION_NOT_FOUND));
		LocalDateTime now = LocalDateTime.now();
		if (emailVerificationPolicy.isCodeExpired(verification.getExpiresAt(), now)) {
			manageVerificationPort.removeVerification(verification);
			throw new AuthException(AuthErrorCode.CODE_EXPIRED);
		}
		if (!verification.matches(command.code(), command.type())) {
			throw new AuthException(AuthErrorCode.CODE_MISMATCH);
		}

		manageVerificationPort.removeVerification(verification);
		recordCompletedEmailVerificationPort.recordCompletedVerification(
				new VerifiedEmail(command.email(), command.type()));
	}

	@Override
	@Transactional
	public void resetPassword(ResetPasswordCommand command) {
		requireValidEmail(command.email());
		requireValidPassword(command.newPassword());
		User user = loadUserForPasswordResetPort.loadPasswordResetUser(command.email())
				.orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
		VerifiedEmail verified = getValidVerifiedEmail(command.email(), VerificationType.PASSWORD_RESET);

		verified.markUsed();
		recordCompletedEmailVerificationPort.recordCompletedVerification(verified);
		user.updatePassword(passwordProtectionPort.protect(command.newPassword()));
		recordUserAccountPort.recordUserAccount(user);
		log.info("event=password_reset outcome=success userId={}", user.getId());
	}

	private void saveVerificationCode(String email, String code, VerificationType type) {
		LocalDateTime expiresAt = emailVerificationPolicy.codeExpiresAt(LocalDateTime.now());
		Verification verification = loadVerificationPort.loadVerification(email, type)
				.orElseGet(() -> new Verification(email, code, type, expiresAt));
		if (verification.getId() != null) {
			verification.updateCode(code, expiresAt);
		}
		manageVerificationPort.storeVerification(verification);
	}

	private VerifiedEmail getValidVerifiedEmail(String email, VerificationType type) {
		VerifiedEmail latest = loadCompletedEmailVerificationPort.loadLatestVerification(email, type)
				.orElseThrow(() -> new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_FOUND));
		if (!latest.isFor(email, type)) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_FOUND);
		}
		if (emailVerificationPolicy.isCompletedVerificationExpired(latest.getVerifiedAt(), LocalDateTime.now())) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_EXPIRED);
		}
		if (latest.isUsed()) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_ALREADY_USED);
		}
		return latest;
	}

	private void requireSelectableFixedCharacter(Long fixedCharacterId) {
		if (fixedCharacterId == null || fixedCharacterId <= 0) {
			throw new AuthException(AuthErrorCode.FIXED_CHARACTER_NOT_FOUND);
		}
		if (!checkSignUpCharacterSelectionPort.isSelectableFixedCharacter(fixedCharacterId)) {
			throw new AuthException(AuthErrorCode.FIXED_CHARACTER_NOT_FOUND);
		}
	}

	private void requireValidEmail(String rawEmail) {
		try {
			new Email(rawEmail);
		} catch (InvalidEmailException exception) {
			throw new AuthException(AuthErrorCode.INVALID_EMAIL);
		}
	}

	private void requireValidPassword(String rawPassword) {
		try {
			passwordPolicy.validate(rawPassword);
		} catch (InvalidPasswordException exception) {
			throw new AuthException(AuthErrorCode.INVALID_PASSWORD);
		}
	}
}
