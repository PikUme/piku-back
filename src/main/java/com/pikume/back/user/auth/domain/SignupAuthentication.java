package com.pikume.back.user.auth.domain;

import com.pikume.back.user.auth.domain.exception.SignupProofException;
import com.pikume.back.user.domain.vo.Email;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.pikume.back.user.auth.domain.exception.SignupProofException.Reason.*;

@Entity
@Table(name = "signup_authentications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignupAuthentication {
    @Id @Column(length = 36)
    private String id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false, length = 64)
    private String callerHash;
    @Column(nullable = false, length = 20)
    private String flowType;
    @Column(nullable = false, length = 20)
    private String method;
    @Column(length = 20)
    private String provider;
    @Column(length = 255)
    private String providerSubject;
    private String verifiedEmail;
    private String passwordHash;
    @Column(length = 20)
    private String emailVerificationSource;
    private Instant emailVerifiedAt;
    @Column(nullable = false)
    private Instant authenticatedAt;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant consumedAt;
    @Column(length = 36)
    private String resultUserId;
    @Column(length = 64)
    private String completionFingerprint;

    private SignupAuthentication(String tokenHash, String callerHash, String method, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.callerHash = Objects.requireNonNull(callerHash);
        this.flowType = "CHAPTERED";
        this.method = method;
        this.authenticatedAt = Objects.requireNonNull(now);
        this.expiresAt = now.plusSeconds(600);
    }

    public static SignupAuthentication email(String tokenHash, String callerHash, String email,
                                               String passwordHash, Instant now) {
        if (passwordHash == null || passwordHash.isBlank()) throw new SignupProofException(INVALID);
        var proof = new SignupAuthentication(tokenHash, callerHash, "EMAIL", now);
        proof.passwordHash = passwordHash;
        proof.verifyEmail(email, now);
        return proof;
    }

    public static SignupAuthentication legacy(String tokenHash, String callerHash, String email, Instant now) {
        var proof = new SignupAuthentication(tokenHash, callerHash, "EMAIL", now);
        proof.flowType = "LEGACY";
        proof.verifyEmail(email, now);
        return proof;
    }

    public static SignupAuthentication social(String tokenHash, String callerHash, String provider,
                                                String subject, String trustedEmail, Instant now) {
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank())
            throw new SignupProofException(INVALID);
        var proof = new SignupAuthentication(tokenHash, callerHash, "SOCIAL", now);
        proof.provider = provider;
        proof.providerSubject = subject;
        if (trustedEmail != null) {
            proof.verifyEmail(trustedEmail, now);
            proof.emailVerificationSource = "PROVIDER";
        }
        return proof;
    }

    public void requireUsable(String submittedCallerHash, Instant now) {
        if (submittedCallerHash == null || !MessageDigest.isEqual(
                callerHash.getBytes(StandardCharsets.UTF_8), submittedCallerHash.getBytes(StandardCharsets.UTF_8)))
            throw new SignupProofException(INVALID);
        requireUnexpired(now);
    }

    public void requireConsentReady() {
        if (!"CHAPTERED".equals(flowType)) throw new SignupProofException(FLOW_MISMATCH);
        if (verifiedEmail == null) throw new SignupProofException(EMAIL_REQUIRED);
        if (consumedAt == null && "EMAIL".equals(method) && passwordHash == null)
            throw new SignupProofException(INVALID);
    }

    public void beginEmailVerification(String finalEmail, Instant now) {
        requireUnexpired(now);
        if (consumedAt != null) throw new SignupProofException(ALREADY_USED);
        if (!"SOCIAL".equals(method) || !"CHAPTERED".equals(flowType)) throw new SignupProofException(FLOW_MISMATCH);
        String email = new Email(finalEmail).value();
        if (!Objects.equals(verifiedEmail, email)) {
            this.verifiedEmail = null;
            this.emailVerifiedAt = null;
            this.emailVerificationSource = null;
        }
    }

    public void verifyEmail(String email, Instant now) {
        requireUnexpired(now);
        if (consumedAt != null) throw new SignupProofException(ALREADY_USED);
        this.verifiedEmail = new Email(email).value();
        this.emailVerifiedAt = now;
        this.emailVerificationSource = "SERVICE";
    }

    public void consume(String userId, String fingerprint, Instant now) {
        requireUnexpired(now);
        if (consumedAt != null) {
            if (!Objects.equals(resultUserId, userId) || !Objects.equals(completionFingerprint, fingerprint))
                throw new SignupProofException(ALREADY_USED);
            return;
        }
        this.resultUserId = Objects.requireNonNull(userId);
        this.completionFingerprint = Objects.requireNonNull(fingerprint);
        this.consumedAt = now;
        this.passwordHash = null;
    }

    public String completedUser(String fingerprint, Instant now) {
        requireUnexpired(now);
        if (consumedAt == null) return null;
        if (!Objects.equals(completionFingerprint, fingerprint)) throw new SignupProofException(ALREADY_USED);
        return resultUserId;
    }

    private void requireUnexpired(Instant now) {
        if (!now.isBefore(expiresAt)) throw new SignupProofException(EXPIRED);
    }
}
