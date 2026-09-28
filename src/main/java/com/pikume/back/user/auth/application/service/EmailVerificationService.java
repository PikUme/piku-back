package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.EmailVerificationDelivery;
import com.pikume.back.user.auth.application.dto.EmailVerificationResult;
import com.pikume.back.user.auth.application.dto.SendEmailVerificationCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCodeCommand;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationPolicyPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationTransactionPort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.domain.vo.Email;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.pikume.back.user.auth.application.exception.EmailVerificationFailure.CODE_EXPIRED;
import static com.pikume.back.user.auth.application.exception.EmailVerificationFailure.EMAIL_SEND_FAILED;
import static com.pikume.back.user.auth.application.exception.EmailVerificationFailure.INVALID_EMAIL;
import static com.pikume.back.user.auth.application.exception.EmailVerificationFailure.INVALID_REQUEST;
import static com.pikume.back.user.auth.application.exception.EmailVerificationFailure.VERIFICATION_INVALID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService implements EmailVerificationUseCase {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final EmailVerificationStorePort store;
	private final EmailVerificationTransactionPort transactions;
	private final EmailVerificationPolicyPort policy;
	private final IssueVerificationEmailPort emailSender;
	private final QueryAllowedEmailUseCase allowedEmails;

	@Override
	public EmailVerificationDelivery sendEmailCode(SendEmailVerificationCommand command) {
		String email = validEmail(command.email());
		requireRequestOrigin(command.requestOriginKey());

		DeliveryReservation reservation = reserveDelivery(email, command.requestOriginKey());
		String code = sendVerificationEmail(email);
		return activateDeliveredCode(reservation, code);
	}

	private void requireRequestOrigin(String requestOriginKey) {
		if (requestOriginKey == null || requestOriginKey.isBlank()) {
			throw fail(INVALID_REQUEST);
		}
	}

	private DeliveryReservation reserveDelivery(String email, String requestOriginKey) {
		return transactions.required(() -> {
			// 첫 발송과 재발송 모두 발송 제한 잠금을 먼저 얻은 뒤 인증 행을 잠근다.
			Instant now = store.reserveEmailSend(
					hash(email.toLowerCase(Locale.ROOT)), hash(requestOriginKey),
					policy.emailHourlyLimit(), policy.originHourlyLimit(), policy.resendSeconds());
			Verification verification = store.lockLatestVerification(email).orElse(null);
			if (verification == null || verification.getVerifiedAt() != null) {
				verification = Verification.emailVerification(
						UUID.randomUUID().toString(), email, now, policy.resendSeconds());
			} else {
				verification.restart(now, policy.resendSeconds());
			}
			store.saveVerification(verification);
			return new DeliveryReservation(verification.getEmailVerificationId(), verification.getSentAt());
		});
	}

	private String sendVerificationEmail(String email) {
		// 발송 제한을 먼저 저장하고 DB 트랜잭션 밖에서 메일을 보낸다.
		String code;
		try {
			code = emailSender.issueVerificationEmail(email);
		} catch (RuntimeException error) {
			throw new EmailVerificationException(EMAIL_SEND_FAILED, error);
		}
		if (code == null || !code.matches("[0-9]{6}")) {
			throw fail(EMAIL_SEND_FAILED);
		}
		return code;
	}

	private EmailVerificationDelivery activateDeliveredCode(DeliveryReservation reservation, String code) {
		return transactions.required(() -> {
			Verification verification = store.lockVerification(reservation.id())
					.orElseThrow(() -> fail(VERIFICATION_INVALID));
			if (!Objects.equals(verification.getSentAt(), reservation.sentAt())
					|| verification.getVerifiedAt() != null) {
				throw fail(VERIFICATION_INVALID);
			}
			Instant now = Instant.now();
			if (!now.isBefore(verification.getExpiresAt().toInstant(ZoneOffset.UTC))) {
				throw fail(CODE_EXPIRED);
			}
			verification.activateCode(code, now);
			return new EmailVerificationDelivery(
					verification.getExpiresAt().toInstant(ZoneOffset.UTC), verification.getResendAvailableAt());
		});
	}

	@Override
	public EmailVerificationResult verifyEmailCode(VerifyEmailCodeCommand command) {
		String email = validEmail(command.email());
		Attempt attempt = transactions.required(() -> {
			Verification verification = store.lockLatestVerification(email)
					.orElseThrow(() -> fail(VERIFICATION_INVALID));
			Instant now = Instant.now();
			String failure = verification.validateCode(command.code(), now, policy.maxCodeAttempts());
			if (failure != null) {
				return new Attempt(EmailVerificationFailure.valueOf(failure), null);
			}
			byte[] bytes = new byte[32];
			RANDOM.nextBytes(bytes);
			String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			verification.verify(hash(token), now);
			return new Attempt(null, new EmailVerificationResult(
					token, verification.getExpiresAt().toInstant(ZoneOffset.UTC)));
		});
		// 오입력 횟수를 먼저 커밋한 뒤 오류를 반환해야 다음 요청에도 제한이 적용된다.
		if (attempt.failure() != null) {
			throw fail(attempt.failure());
		}
		return attempt.result();
	}

	@Override
	public void purgeExpiredVerifications() {
		store.purgeExpired(Instant.now());
	}

	private String validEmail(String email) {
		try {
			if (email == null || email.length() > 255) {
				throw new IllegalArgumentException();
			}
			new Email(email);
		} catch (RuntimeException error) {
			throw fail(INVALID_EMAIL);
		}
		if (!allowedEmails.isEmailAllowed(email)) {
			throw fail(INVALID_EMAIL);
		}
		return email;
	}

	public static String hash(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	private static EmailVerificationException fail(EmailVerificationFailure reason) {
		return new EmailVerificationException(reason);
	}

	private record DeliveryReservation(String id, Instant sentAt) {}

	private record Attempt(EmailVerificationFailure failure, EmailVerificationResult result) {}
}
