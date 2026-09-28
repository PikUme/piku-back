package com.pikume.back.user.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.vo.Email;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Entity
@Table(name = "verification")
@Getter
@NoArgsConstructor
public class Verification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String code;

	// 회원가입 인증의 발송·만료·소비 시각은 모두 한국 시간으로 저장하고 비교한다.
	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private VerificationType type;

	@Column(unique = true, length = 36)
	private String emailVerificationId;

	@Column(unique = true, length = 64)
	private String verificationTokenHash;

	private Integer attempts;

	private LocalDateTime sentAt;

	private LocalDateTime resendAvailableAt;

	private LocalDateTime deliveryCompletedAt;

	private LocalDateTime verifiedAt;

	private LocalDateTime consumedAt;

	public static Verification emailVerification(String id, String email, LocalDateTime now, int resendSeconds) {
		Verification verification = new Verification(email, "PENDING", VerificationType.SIGN_UP,
				now.plusSeconds(300));
		verification.emailVerificationId = id;
		verification.restart(now, resendSeconds);
		return verification;
	}

	public void restart(LocalDateTime now, int resendSeconds) {
		if (verifiedAt != null) {
			throw new IllegalStateException("Verified email cannot be restarted");
		}
		code = "PENDING";
		attempts = 0;
		sentAt = now;
		resendAvailableAt = now.plusSeconds(resendSeconds);
		deliveryCompletedAt = null;
		expiresAt = now.plusSeconds(300);
	}

	public void activateCode(String rawCode, LocalDateTime now) {
		code = hash(rawCode);
		deliveryCompletedAt = now;
	}

	// 예외 대신 실패 사유를 반환해, 서비스에서 오입력 횟수를 커밋한 뒤 오류를 전달한다.
	public String validateCode(String submittedCode, LocalDateTime now, int maxAttempts) {
		if (emailVerificationId == null || type != VerificationType.SIGN_UP || deliveryCompletedAt == null) {
			return "VERIFICATION_INVALID";
		}
		if (verifiedAt != null) {
			return "VERIFICATION_ALREADY_COMPLETED";
		}
		if (!now.isBefore(expiresAt)) {
			return "CODE_EXPIRED";
		}
		if (attempts >= maxAttempts) {
			return "ATTEMPTS_EXHAUSTED";
		}
		if (submittedCode == null || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
				hash(submittedCode).getBytes(StandardCharsets.UTF_8))) {
			attempts++;
			return "CODE_MISMATCH";
		}
		return null;
	}

	public void verify(String tokenHash, LocalDateTime now) {
		if (verifiedAt != null || consumedAt != null) {
			throw new IllegalStateException("Email already verified");
		}
		verificationTokenHash = tokenHash;
		verifiedAt = now;
		code = "VERIFIED";
		expiresAt = now.plusSeconds(600);
	}

	public String validateToken(LocalDateTime now) {
		if (type != VerificationType.SIGN_UP || verifiedAt == null || verificationTokenHash == null) {
			return "TOKEN_INVALID";
		}
		if (!now.isBefore(expiresAt)) {
			return "TOKEN_EXPIRED";
		}
		if (consumedAt != null) {
			return "TOKEN_ALREADY_USED";
		}
		return null;
	}

	public void consumeVerifiedEmail(LocalDateTime now) {
		if (validateToken(now) != null) {
			throw new IllegalStateException("Email verification is not usable");
		}
		consumedAt = now;
	}

	private static String hash(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public Verification(String email, String code, VerificationType type, LocalDateTime expiresAt) {
		this.email = new Email(email).value();
		this.code = code;
		this.type = type;
		this.expiresAt = expiresAt;
	}

	public void updateCode(String newCode, LocalDateTime newExpiresAt) {
		code = newCode;
		expiresAt = newExpiresAt;
	}

	public boolean matches(String submittedCode, VerificationType submittedType) {
		return type == submittedType && code.equals(submittedCode);
	}
}
