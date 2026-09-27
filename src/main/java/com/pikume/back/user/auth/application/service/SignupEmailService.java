package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.EmailSignupChallengeCommand;
import com.pikume.back.user.auth.application.dto.EmailSignupChallengeResult;
import com.pikume.back.user.auth.application.exception.SignupFailure;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.in.SendSignupEmailCodeUseCase;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.port.out.SignupPolicyPort;
import com.pikume.back.user.auth.application.port.out.SignupStorePort;
import com.pikume.back.user.auth.application.port.out.SignupTransactionPort;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.domain.vo.Email;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import static com.pikume.back.user.auth.application.exception.SignupFailure.*;

@Service
@RequiredArgsConstructor
public class SignupEmailService implements SendSignupEmailCodeUseCase {
    private final SignupStorePort store;
    private final SignupTransactionPort transactions;
    private final SignupPolicyPort policy;
    private final IssueVerificationEmailPort emailSender;
    private final QueryAllowedEmailUseCase allowedEmails;

    @Override
    public EmailSignupChallengeResult sendEmailCode(EmailSignupChallengeCommand command) {
        String email = validSignupEmail(command.email());
        String caller = requiredHash(command.callerBinding());
        String origin = requiredHash(command.requestOriginKey());
        var reservation = transactions.required(() -> {
            Verification challenge = null;
            if (command.challengeId() != null) {
                challenge = store.lockChallenge(command.challengeId()).orElseThrow(() -> fail(CHALLENGE_INVALID));
                if (!challenge.isBoundTo(email, caller) || challenge.getConsumedAt() != null) throw fail(CHALLENGE_INVALID);
            }
            // Rejection after the challenge/rate guard locks rolls back the reservation too.
            Instant now = store.reserveEmailSend(hash(email.toLowerCase(Locale.ROOT)), origin, policy.emailHourlyLimit(), policy.originHourlyLimit(), policy.resendSeconds());
            if (challenge != null) {
                if (now.isBefore(challenge.getResendAvailableAt())) throw fail(RATE_LIMITED);
                challenge.restartSignup(now, policy.resendSeconds());
            } else {
                challenge = Verification.signupChallenge(UUID.randomUUID().toString(), email, caller, now, policy.resendSeconds());
            }
            store.saveChallenge(challenge);
            return new ChallengeReservation(challenge.getChallengeId(), challenge.getSentAt());
        });
        // Rate reservation commits before external mail. Failed/unknown delivery is never usable or reported successful.
        String code;
        try {
            code = emailSender.issueVerificationEmail(email);
        } catch (RuntimeException error) {
            throw new SignupFlowException(EMAIL_SEND_FAILED, error);
        }
        if (code == null || !code.matches("[0-9]{6}")) throw fail(EMAIL_SEND_FAILED);
        return transactions.required(() -> {
            Verification v = store.lockChallenge(reservation.id()).orElseThrow(() -> fail(CHALLENGE_INVALID));
            if (!Objects.equals(v.getSentAt(), reservation.sentAt()) || v.getConsumedAt()!=null) throw fail(CHALLENGE_INVALID);
            if (!Instant.now().isBefore(v.getExpiresAt().toInstant(ZoneOffset.UTC))) throw fail(CODE_EXPIRED);
            v.activateSignupCode(code, Instant.now());
            return new EmailSignupChallengeResult(v.getChallengeId(), v.getExpiresAt().toInstant(ZoneOffset.UTC), v.getResendAvailableAt());
        });
    }

    private String validSignupEmail(String email) {
        try {
            if (email == null || email.length() > 255) throw new IllegalArgumentException();
            new Email(email);
        } catch (RuntimeException error) {
            throw fail(INVALID_EMAIL);
        }
        if (!allowedEmails.isEmailAllowed(email)) throw fail(INVALID_EMAIL);
        return email;
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requiredHash(String raw) {
        if (raw==null || raw.isBlank())throw fail(INVALID_REQUEST);
        return hash(raw);
    }

    private static SignupFlowException fail(SignupFailure reason) {
        return new SignupFlowException(reason);
    }

    private record ChallengeReservation(String id, Instant sentAt) {
    }
}
