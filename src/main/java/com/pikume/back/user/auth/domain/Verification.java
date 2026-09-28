package com.pikume.back.user.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.vo.Email;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Entity
@Table(name = "verification")
@Getter
@NoArgsConstructor
public class Verification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String email;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private LocalDateTime expiresAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private VerificationType type;
    @Column(unique = true, length = 36) private String emailVerificationId;
    @Column(unique = true, length = 64) private String verificationTokenHash;
    private Integer attempts;
    private Instant sentAt;
    private Instant resendAvailableAt;
    private Instant deliveryCompletedAt;
    private Instant verifiedAt;
    private Instant consumedAt;

    public static Verification emailVerification(String id, String email, Instant now, int resendSeconds) {
        Verification verification = new Verification(email, "PENDING", VerificationType.SIGN_UP,
                LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC));
        verification.emailVerificationId = id;
        verification.restart(now, resendSeconds);
        return verification;
    }

    public void restart(Instant now, int resendSeconds) {
        if (verifiedAt != null) throw new IllegalStateException("Verified email cannot be restarted");
        code = "PENDING";
        attempts = 0;
        sentAt = now;
        resendAvailableAt = now.plusSeconds(resendSeconds);
        deliveryCompletedAt = null;
        expiresAt = LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC);
    }

    public void activateCode(String rawCode, Instant now) {
        code = hash(rawCode);
        deliveryCompletedAt = now;
    }

    /** Return failures so invalid attempts can commit before an API error is raised. */
    public String validateCode(String submittedCode, Instant now, int maxAttempts) {
        if (emailVerificationId == null || type != VerificationType.SIGN_UP || deliveryCompletedAt == null)
            return "VERIFICATION_INVALID";
        if (verifiedAt != null) return "VERIFICATION_ALREADY_COMPLETED";
        if (!now.isBefore(expiresAt.toInstant(ZoneOffset.UTC))) return "CODE_EXPIRED";
        if (attempts >= maxAttempts) return "ATTEMPTS_EXHAUSTED";
        if (submittedCode == null || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                hash(submittedCode).getBytes(StandardCharsets.UTF_8))) {
            attempts++;
            return "CODE_MISMATCH";
        }
        return null;
    }

    public void verify(String tokenHash, Instant now) {
        if (verifiedAt != null || consumedAt != null) throw new IllegalStateException("Email already verified");
        verificationTokenHash = tokenHash;
        verifiedAt = now;
        code = "VERIFIED";
        expiresAt = LocalDateTime.ofInstant(now.plusSeconds(600), ZoneOffset.UTC);
    }

    public String validateToken(Instant now) {
        if (type != VerificationType.SIGN_UP || verifiedAt == null || verificationTokenHash == null) return "TOKEN_INVALID";
        if (!now.isBefore(expiresAt.toInstant(ZoneOffset.UTC))) return "TOKEN_EXPIRED";
        if (consumedAt != null) return "TOKEN_ALREADY_USED";
        return null;
    }

    public void consumeVerifiedEmail(Instant now) {
        if (validateToken(now) != null) throw new IllegalStateException("Email verification is not usable");
        consumedAt = now;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public Verification(String email, String code, VerificationType type, LocalDateTime expiresAt) {
        this.email = new Email(email).value(); this.code = code; this.type = type; this.expiresAt = expiresAt;
    }
    public void updateCode(String newCode, LocalDateTime newExpiresAt) { code = newCode; expiresAt = newExpiresAt; }
    public boolean matches(String submittedCode, VerificationType submittedType) {
        return type == submittedType && code.equals(submittedCode);
    }
}
