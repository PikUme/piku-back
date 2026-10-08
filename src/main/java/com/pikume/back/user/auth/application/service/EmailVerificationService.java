package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.dto.SignupEmailVerification;
import com.pikume.back.user.auth.application.dto.SignupVerificationSent;
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.domain.exception.InvalidEmailException;
import com.pikume.back.user.domain.vo.Email;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService implements EmailVerificationUseCase {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final EmailVerificationStorePort store;
	private final IssueVerificationEmailPort emailSender;
	private final QueryAllowedEmailUseCase allowedEmails;

	public EmailVerificationService(EmailVerificationStorePort store, IssueVerificationEmailPort emailSender,
			QueryAllowedEmailUseCase allowedEmails) {
		this.store = store;
		this.emailSender = emailSender;
		this.allowedEmails = allowedEmails;
	}

	@Override
	public SignupVerificationSent sendSignUpVerificationEmail(String rawEmail) {
		String email = validEmail(rawEmail);
		String emailKey = hash(email);
		String code = createVerificationCode();
		String generation = UUID.randomUUID().toString();
		EmailVerificationStorePort.ReservationResult reservation =
				store.reserve(emailKey, generation, hash(code), UUID.randomUUID().toString());
		if (reservation.status() == EmailVerificationStorePort.ReservationStatus.RATE_LIMITED) {
			throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED,
					reservation.resendAvailableAt());
		}
		try {
			emailSender.deliverVerificationCode(email, code);
		} catch (RuntimeException exception) {
			throw new EmailVerificationException(EmailVerificationFailure.EMAIL_SEND_FAILED, exception, null,
					reservation.resendAvailableAt());
		}
		LocalDateTime expiresAt = store.activate(emailKey, generation)
				.orElseThrow(() -> new EmailVerificationException(EmailVerificationFailure.VERIFICATION_INVALID));
		return new SignupVerificationSent(expiresAt, reservation.resendAvailableAt());
	}

	@Override
	public SignupEmailVerification verifySignUpVerificationCode(String rawEmail, String code) {
		String email = validEmail(rawEmail);
		String token = UUID.randomUUID().toString();
		EmailVerificationStorePort.VerificationResult result = store.verify(
				hash(email), hash(code == null ? "" : code), UUID.randomUUID().toString(), hash(token));
		switch (result.status()) {
			case VERIFIED -> { return new SignupEmailVerification(token, result.proof().expiresAt()); }
			case NOT_FOUND, INACTIVE -> throw new EmailVerificationException(
					EmailVerificationFailure.VERIFICATION_INVALID);
			case EXPIRED -> throw new EmailVerificationException(EmailVerificationFailure.CODE_EXPIRED);
			case MISMATCH -> throw new EmailVerificationException(EmailVerificationFailure.CODE_MISMATCH);
			case ATTEMPTS_EXHAUSTED -> throw new EmailVerificationException(
					EmailVerificationFailure.ATTEMPTS_EXHAUSTED, result.retryAt());
		}
		throw new IllegalStateException("Unhandled signup email verification result");
	}

	private String validEmail(String rawEmail) {
		String email;
		try {
			if (rawEmail == null || rawEmail.length() > 255) {
				throw new InvalidEmailException("이메일은 필수 값입니다.");
			}
			email = new Email(rawEmail).value().toLowerCase(Locale.ROOT);
		} catch (InvalidEmailException exception) {
			throw new EmailVerificationException(EmailVerificationFailure.INVALID_EMAIL);
		}
		if (!allowedEmails.isEmailAllowed(email)) {
			throw new EmailVerificationException(EmailVerificationFailure.INVALID_EMAIL);
		}
		return email;
	}

	private static String createVerificationCode() {
		return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
	}

	public static String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
