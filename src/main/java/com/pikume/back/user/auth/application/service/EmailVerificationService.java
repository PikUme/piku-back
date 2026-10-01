package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.auth.application.dto.EmailVerificationDelivery;
import com.pikume.back.user.auth.application.dto.EmailVerificationReservation;
import com.pikume.back.user.auth.application.dto.EmailVerificationResult;
import com.pikume.back.user.auth.application.dto.SendEmailVerificationCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCodeCommand;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationPolicyPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.domain.vo.Email;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService implements EmailVerificationUseCase {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final EmailVerificationStorePort store;
	private final EmailVerificationPolicyPort policy;
	private final IssueVerificationEmailPort emailSender;
	private final QueryAllowedEmailUseCase allowedEmails;
	private final CheckUserUniquenessPort userUniqueness;

	public EmailVerificationService(EmailVerificationStorePort store, EmailVerificationPolicyPort policy,
			IssueVerificationEmailPort emailSender, QueryAllowedEmailUseCase allowedEmails,
			CheckUserUniquenessPort userUniqueness) {
		this.store = store;
		this.policy = policy;
		this.emailSender = emailSender;
		this.allowedEmails = allowedEmails;
		this.userUniqueness = userUniqueness;
	}

	@Override
	public EmailVerificationDelivery sendEmailCode(SendEmailVerificationCommand command) {
		String email = validEmail(command.email());
		if (userUniqueness.isEmailRegistered(email)) {
			throw new EmailVerificationException(EmailVerificationFailure.EMAIL_ALREADY_EXISTS);
		}
		String generation = UUID.randomUUID().toString();
		String code = createVerificationCode();
		EmailVerificationReservation reservation = store.prepare(
				hash(email), generation, hash(code), policy.emailHourlyLimit(), policy.resendSeconds());
		sendVerificationEmail(email, code, reservation.resendAvailableAt());
		if (!store.activate(hash(email), generation, hash(code))) {
			throw new EmailVerificationException(EmailVerificationFailure.VERIFICATION_INVALID);
		}
		return new EmailVerificationDelivery(reservation.expiresAt(), reservation.resendAvailableAt());
	}

	@Override
	public EmailVerificationResult verifyEmailCode(VerifyEmailCodeCommand command) {
		String email = validEmail(command.email());
		byte[] tokenBytes = new byte[32];
		RANDOM.nextBytes(tokenBytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
		EmailVerificationStorePort.EmailVerificationAttempt attempt = store.verify(
				hash(email), hash(command.code() == null ? "" : command.code()), hash(token), token,
				policy.maxCodeAttempts());
		if (attempt.failure() != null) {
			throw new EmailVerificationException(attempt.failure());
		}
		return attempt.result();
	}

	private void sendVerificationEmail(String email, String code, LocalDateTime resendAvailableAt) {
		try {
			emailSender.deliverVerificationCode(email, code);
		} catch (RuntimeException exception) {
			throw new EmailVerificationException(
					EmailVerificationFailure.EMAIL_SEND_FAILED, resendAvailableAt, exception);
		}
	}

	private String validEmail(String rawEmail) {
		String email;
		try {
			if (rawEmail == null || rawEmail.length() > 255) throw new IllegalArgumentException();
			email = new Email(rawEmail).value().toLowerCase(Locale.ROOT);
		} catch (RuntimeException exception) {
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

	public static LocalDateTime kstNow() {
		return LocalDateTime.now(KST).truncatedTo(ChronoUnit.MILLIS);
	}
}
