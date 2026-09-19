package com.pikume.back.user.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.vo.Email;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
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

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private VerificationType type;

    @Column(unique = true, length = 36)
    private String challengeId;
    @Column(length = 64)
    private String callerHash;
    @Column(length = 64)
    private String signupProofHash;
    private Integer attempts;
    private Instant sentAt;
    private Instant resendAvailableAt;
    private Instant consumedAt;
    private Instant deliveryCompletedAt;

    public static Verification signupChallenge(String id, String email, String callerHash,
            String proofHash, Instant now, int resendSeconds) {
        Verification challenge = new Verification(email, "PENDING", VerificationType.SIGN_UP,
                LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC));
        challenge.challengeId = id;
        challenge.callerHash = callerHash;
        challenge.signupProofHash = proofHash;
        challenge.attempts = 0;
        challenge.sentAt = now;
        challenge.resendAvailableAt = now.plusSeconds(resendSeconds);
        return challenge;
    }

    public boolean isBoundTo(String email, String callerHash, String proofHash) {
        return Objects.equals(this.email, email) && Objects.equals(this.callerHash, callerHash)
                && Objects.equals(this.signupProofHash, proofHash) && type == VerificationType.SIGN_UP;
    }

    public void restartSignup(Instant now, int resendSeconds) {
        this.code = "PENDING";
        this.attempts = 0;
        this.sentAt = now;
        this.resendAvailableAt = now.plusSeconds(resendSeconds);
        this.deliveryCompletedAt = null;
        this.expiresAt = LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC);
    }

    public void activateSignupCode(String rawCode, Instant now) {
        this.code = codeHash(rawCode);
        this.deliveryCompletedAt = now;
    }

    /** Returns failure without throwing so a failed attempt can be committed. */
    public String validateSignup(String email, String callerHash, String proofHash, String submittedCode,
            Instant now, int maxAttempts) {
        if (challengeId == null || !isBoundTo(email, callerHash, proofHash) || consumedAt != null
                || deliveryCompletedAt == null) return "CHALLENGE_INVALID";
        if (!now.isBefore(expiresAt.toInstant(ZoneOffset.UTC))) return "CODE_EXPIRED";
        if (attempts >= maxAttempts) return "ATTEMPTS_EXHAUSTED";
        if (submittedCode == null || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                codeHash(submittedCode).getBytes(StandardCharsets.UTF_8))) {
            attempts++;
            return "CODE_MISMATCH";
        }
        return null;
    }

    public void consumeSignup(Instant now) { this.consumedAt = now; this.code = "CONSUMED"; }

    private static String codeHash(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

	public Verification(String email, String code, VerificationType type, LocalDateTime expiresAt) {
		this.email = new Email(email).value();
		this.code = code;
		this.type = type;
		this.expiresAt = expiresAt;
	}

	public void updateCode(String newCode, LocalDateTime newExpiresAt) {
		this.code = newCode;
		this.expiresAt = newExpiresAt;
	}

	public boolean matches(String submittedCode, VerificationType submittedType) {
		return type == submittedType && code.equals(submittedCode);
	}
}
