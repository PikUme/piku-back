package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.*;
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
import java.util.*;
import static com.pikume.back.user.auth.application.exception.EmailVerificationFailure.*;

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
        if (command.requestOriginKey() == null || command.requestOriginKey().isBlank()) throw fail(INVALID_REQUEST);
        var reservation = transactions.required(() -> {
            // Guard precedes the email row lock for every send, including the first absent row.
            Instant now = store.reserveEmailSend(hash(email.toLowerCase(Locale.ROOT)), hash(command.requestOriginKey()),
                    policy.emailHourlyLimit(), policy.originHourlyLimit(), policy.resendSeconds());
            Verification verification = store.lockLatestVerification(email).orElse(null);
            if (verification == null || verification.getVerifiedAt() != null) {
                verification = Verification.emailVerification(UUID.randomUUID().toString(), email, now, policy.resendSeconds());
            } else {
                verification.restart(now, policy.resendSeconds());
            }
            store.saveVerification(verification);
            return new DeliveryReservation(verification.getEmailVerificationId(), verification.getSentAt());
        });
        String code;
        try { code = emailSender.issueVerificationEmail(email); }
        catch (RuntimeException error) { throw new EmailVerificationException(EMAIL_SEND_FAILED, error); }
        if (code == null || !code.matches("[0-9]{6}")) throw fail(EMAIL_SEND_FAILED);
        return transactions.required(() -> {
            Verification verification = store.lockVerification(reservation.id()).orElseThrow(() -> fail(VERIFICATION_INVALID));
            if (!Objects.equals(verification.getSentAt(), reservation.sentAt()) || verification.getVerifiedAt() != null)
                throw fail(VERIFICATION_INVALID);
            Instant now = Instant.now();
            if (!now.isBefore(verification.getExpiresAt().toInstant(ZoneOffset.UTC))) throw fail(CODE_EXPIRED);
            verification.activateCode(code, now);
            return new EmailVerificationDelivery(verification.getExpiresAt().toInstant(ZoneOffset.UTC), verification.getResendAvailableAt());
        });
    }

    @Override
    public EmailVerificationResult verifyEmailCode(VerifyEmailCodeCommand command) {
        String email = validEmail(command.email());
        Attempt attempt = transactions.required(() -> {
            Verification verification = store.lockLatestVerification(email).orElseThrow(() -> fail(VERIFICATION_INVALID));
            Instant now = Instant.now();
            String failure = verification.validateCode(command.code(), now, policy.maxCodeAttempts());
            if (failure != null) return new Attempt(EmailVerificationFailure.valueOf(failure), null);
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            verification.verify(hash(token), now);
            return new Attempt(null, new EmailVerificationResult(token, verification.getExpiresAt().toInstant(ZoneOffset.UTC)));
        });
        if (attempt.failure() != null) throw fail(attempt.failure());
        return attempt.result();
    }

    @Override
    public void purgeExpiredVerifications() { store.purgeExpired(Instant.now()); }

    private String validEmail(String email) {
        try {
            if (email == null || email.length() > 255) throw new IllegalArgumentException();
            new Email(email);
        } catch (RuntimeException error) { throw fail(INVALID_EMAIL); }
        if (!allowedEmails.isEmailAllowed(email)) throw fail(INVALID_EMAIL);
        return email;
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static EmailVerificationException fail(EmailVerificationFailure reason) { return new EmailVerificationException(reason); }
    private record DeliveryReservation(String id, Instant sentAt) {}
    private record Attempt(EmailVerificationFailure failure, EmailVerificationResult result) {}
}
