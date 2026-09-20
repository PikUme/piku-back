package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.AgreementAcceptance;
import com.pikume.back.user.auth.application.dto.EmailSignupAuthenticationCommand;
import com.pikume.back.user.auth.application.dto.EmailSignupChallengeCommand;
import com.pikume.back.user.auth.application.dto.EmailSignupChallengeResult;
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.SignupAgreementCommand;
import com.pikume.back.user.auth.application.dto.SignupAgreementDocument;
import com.pikume.back.user.auth.application.dto.SignupConfiguration;
import com.pikume.back.user.auth.application.dto.SignupNextAction;
import com.pikume.back.user.auth.application.dto.SignupProgress;
import com.pikume.back.user.auth.application.dto.SignupProofResult;
import com.pikume.back.user.auth.application.exception.SignupFailure;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.port.in.CleanupSignupUseCase;
import com.pikume.back.user.auth.application.port.in.LegacySignupProofUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.in.QuerySignupAgreementUseCase;
import com.pikume.back.user.auth.application.port.in.QuerySignupConfigurationUseCase;
import com.pikume.back.user.auth.application.port.in.SignUpUseCase;
import com.pikume.back.user.auth.application.port.in.SignupFlowUseCase;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.ResolveDefaultSignupCharacterPort;
import com.pikume.back.user.auth.application.port.out.SignupPolicyPort;
import com.pikume.back.user.auth.application.port.out.SignupStorePort;
import com.pikume.back.user.auth.application.port.out.SignupTransactionPort;
import com.pikume.back.user.auth.domain.SignupAuthentication;
import com.pikume.back.user.auth.domain.UserAgreement;
import com.pikume.back.user.auth.domain.UserOAuthAccount;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.exception.SignupProofException;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.service.PasswordPolicy;
import com.pikume.back.user.domain.service.DefaultSignupNicknamePolicy;
import com.pikume.back.user.domain.vo.Nickname;
import com.pikume.back.user.application.port.out.NicknameHoldPort;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import static com.pikume.back.user.auth.application.exception.SignupFailure.*;

@Service
@RequiredArgsConstructor
public class SignupFlowService implements SignupFlowUseCase, QuerySignupAgreementUseCase,
        QuerySignupConfigurationUseCase, LegacySignupProofUseCase, CleanupSignupUseCase {

    private final SignupStorePort store;

    private final SignupTransactionPort transactions;

    private final SignupPolicyPort policy;

    private final PasswordProtectionPort passwords;

    private final IssueVerificationEmailPort emailSender;

    private final ResolveDefaultSignupCharacterPort characters;

    private final SignUpUseCase legacySignup;

    private final PasswordPolicy passwordPolicy;

    private final QueryAllowedEmailUseCase allowedEmails;

    private final NicknameHoldPort nicknameHolds;

    private final CheckUserUniquenessPort userUniqueness;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public SignupConfiguration querySignupConfiguration() {
        return new SignupConfiguration(policy.enabled(), !policy.enabled() && policy.legacySignupEnabled());
    }

    @Override
    public List<SignupAgreementDocument> querySignupAgreements() {
        requireEnabled();
        return List.copyOf(policy.agreements());
    }

    @Override
    public EmailSignupChallengeResult sendEmailCode(EmailSignupChallengeCommand command) {
        requireEnabled();
        String email = validSignupEmail(command.email());
        String caller = requiredHash(command.callerBinding());
        String origin = requiredHash(command.requestOriginKey());
        var reservation = tx(() -> {
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
        return tx(() -> {
            Verification v = store.lockChallenge(reservation.id()).orElseThrow(() -> fail(CHALLENGE_INVALID));
            if (!Objects.equals(v.getSentAt(), reservation.sentAt()) || v.getConsumedAt()!=null) throw fail(CHALLENGE_INVALID);
            if (!Instant.now().isBefore(v.getExpiresAt().toInstant(ZoneOffset.UTC))) throw fail(CODE_EXPIRED);
            v.activateSignupCode(code, Instant.now());
            return new EmailSignupChallengeResult(v.getChallengeId(), v.getExpiresAt().toInstant(ZoneOffset.UTC), v.getResendAvailableAt());
        });
    }

    @Override
    public SignupProofResult authenticateEmail(EmailSignupAuthenticationCommand command) {
        requireEnabled();
        validSignupEmail(command.email());
        requiredHash(command.callerBinding());
        try {
            passwordPolicy.validate(command.password());
        } catch (IllegalArgumentException error) {
            throw fail(INVALID_PASSWORD);
        }
        Attempt result = tx(() -> {
            Verification challenge = store.lockChallenge(command.challengeId()).orElseThrow(() -> fail(CHALLENGE_INVALID));
            Instant now = Instant.now();
            String invalid = challenge.validateSignup(command.email(), hash(command.callerBinding()), command.code(), now, policy.maxCodeAttempts());
            if (invalid != null) return new Attempt(SignupFailure.valueOf(invalid), null);
            String protectedPassword = passwords.protect(command.password());
            String raw = token();
            SignupAuthentication proof = SignupAuthentication.email(hash(raw), hash(command.callerBinding()), command.email(), protectedPassword, now);
            store.saveProof(proof); challenge.consumeSignup(now);
            return new Attempt(null, new SignupProofResult(raw, proofProgress(proof)));
        });
        return completedAttempt(result);
    }

    @Override
    public SignupProofResult agree(SignupAgreementCommand command) {
        requireEnabled();
        String fingerprint=fingerprint(command.agreements());
        for (int attempt=0; attempt<3; attempt++) {
            try {
                return tx(() -> agreeInTransaction(command, fingerprint));
            } catch (SignupFlowException error) {
                if (error.getReason()==NICKNAME_COLLISION && attempt<2) continue;
                if (error.getReason()==EMAIL_ALREADY_REGISTERED || error.getReason()==ACCOUNT_LINK_CONFLICT)
                return recoverSocialConsent(command, fingerprint, error);
                throw error;
            }
        }
        throw fail(NICKNAME_COLLISION);
    }

    private SignupProofResult agreeInTransaction(SignupAgreementCommand command, String fingerprint) {
        SignupAuthentication proof=loadProof(command.proof(), command.callerBinding());
        // Acquire before any snapshot reads: generated defaults compete with every nickname writer.
        nicknameHolds.lockNicknameWrites();
        Instant now=Instant.now();
        proof.requireUsable(requiredHash(command.callerBinding()),now);
        proof.requireConsentReady();
        String completed=proof.completedUser(fingerprint, now);
        if (completed!=null) return new SignupProofResult(command.proof(), userProgress(activeUser(completed), proof.getExpiresAt()));
        if ("SOCIAL".equals(proof.getMethod())) {
            var account=store.findAccount(proof.getProvider(), proof.getProviderSubject());
            if (account.isPresent()) return consumeForLinkedUser(command.proof(), proof, account.get(), fingerprint, now);
        }
        List<SignupAgreementDocument> documents=validateAgreements(command.agreements());
        validSignupEmail(proof.getVerifiedEmail());
        if (store.findUserByEmail(proof.getVerifiedEmail()).isPresent()) throw fail(EMAIL_ALREADY_REGISTERED);
        Long characterId=characters.resolveDefaultSignupCharacter();
        if (characterId==null || characterId<=0) throw fail(DEFAULT_CHARACTER_UNAVAILABLE);
        Nickname defaultNickname=availableDefaultNickname(proof.getVerifiedEmail());
        User user=store.createUser(User.pending(proof.getVerifiedEmail(), proof.getPasswordHash(), defaultNickname.value(), characterId));
        for (AgreementAcceptance acceptance:command.agreements()) {
            SignupAgreementDocument d=documents.stream().filter(v -> v.type().equals(acceptance.type())).findFirst().orElseThrow();
            store.recordAgreement(new UserAgreement(user.getId(), d.type(), d.version(), d.content(), acceptance.agreed(), now));
        }
        if ("SOCIAL".equals(proof.getMethod())) link(user.getId(), proof.getProvider(), proof.getProviderSubject(), now);
        proof.consume(user.getId(), fingerprint, now);
        return new SignupProofResult(command.proof(), new SignupProgress(SignupNextAction.PROFILE, user.getEmail(), user.getId(), "REQUIRED", proof.getExpiresAt()));
    }

    private Nickname availableDefaultNickname(String email) {
        for(int attempt=0;attempt<20;attempt++) {
            int suffix=attempt==0?0:1000+RANDOM.nextInt(9000);
            Nickname candidate=DefaultSignupNicknamePolicy.candidate(email,suffix);
            if(!userUniqueness.isNicknameInUse(candidate) && !nicknameHolds.isHeld(candidate,Instant.now())) return candidate;
        }
        throw fail(NICKNAME_COLLISION);
    }

    private SignupProofResult recoverSocialConsent(SignupAgreementCommand c, String fingerprint, SignupFlowException original) {
        return tx(() -> {
            SignupAuthentication proof=loadProof(c.proof(), c.callerBinding());Instant now=Instant.now();
            if (!"SOCIAL".equals(proof.getMethod())) throw original;
            UserOAuthAccount account=store.findAccount(proof.getProvider(), proof.getProviderSubject()).orElseThrow(() -> original);
            return consumeForLinkedUser(c.proof(), proof, account, fingerprint, now);
        });
    }

    private SignupProofResult consumeForLinkedUser(String raw, SignupAuthentication proof, UserOAuthAccount account, String fingerprint, Instant now) {
        User user=activeUser(account.getUserId());
        proof.consume(user.getId(), fingerprint, now);
        return new SignupProofResult(raw, userProgress(user, proof.getExpiresAt()));
    }

    private List<SignupAgreementDocument> validateAgreements(List<AgreementAcceptance> acceptances) {
        if (acceptances==null || acceptances.isEmpty()) throw fail(AGREEMENTS_REQUIRED);
        List<SignupAgreementDocument> docs=policy.agreements();
        if (docs.isEmpty() || docs.stream().noneMatch(SignupAgreementDocument::required)) throw fail(AGREEMENTS_REQUIRED);
        Map<String, AgreementAcceptance> submitted=new HashMap<>();
        for (AgreementAcceptance a:acceptances) {
            if (a==null || a.type()==null || a.version()==null || submitted.put(a.type(), a)!=null) throw fail(INVALID_REQUEST);
            var doc=docs.stream().filter(d -> d.type().equals(a.type())).findFirst().orElseThrow(() -> fail(AGREEMENT_VERSION_MISMATCH));
            if (!doc.version().equals(a.version())) throw fail(AGREEMENT_VERSION_MISMATCH);
        }
        for (SignupAgreementDocument doc:docs) {
            AgreementAcceptance a=submitted.get(doc.type());
            if (doc.required() && (a==null || !a.agreed())) throw fail(AGREEMENTS_REQUIRED);
        }
        return docs;
    }

    @Override
    public SignupProgress progress(String proof, String callerBinding) {
        if (proof==null || proof.isBlank()) return new SignupProgress(SignupNextAction.AUTHENTICATE, null, null, null, null);
        return tx(() -> {
            var authentication = loadProof(proof, callerBinding);
            if (!"CHAPTERED".equals(authentication.getFlowType())) throw fail(FLOW_MISMATCH);
            return proofProgress(authentication);
        });
    }

    @Override
    public SignupProofResult issueLegacyProof(String email, String callerBinding) {
        requireLegacy();
        new Email(email);
        return tx(() -> {
            String raw=token();var proof=SignupAuthentication.legacy(hash(raw), requiredHash(callerBinding), email, Instant.now());
            store.saveProof(proof);return new SignupProofResult(raw, proofProgress(proof));
        });
    }

    @Override
    public void completeLegacy(SignUpCommand command, String raw, String callerBinding) {
        requireLegacy();
        tx(() -> {
            var proof=loadProof(raw, callerBinding);Instant now=Instant.now();
            if (!"LEGACY".equals(proof.getFlowType())) throw fail(FLOW_MISMATCH);
            if (!Objects.equals(proof.getVerifiedEmail(), command.email())) throw fail(PROOF_INVALID);
            String fingerprint=hash(frame(command.email())+frame(command.password())+frame(command.nickname())+frame(String.valueOf(command.fixedCharacterId())));
            if (proof.completedUser(fingerprint, now)!=null) return null;
            legacySignup.signUp(command);
            User user=store.findUserByEmail(command.email()).orElseThrow(() -> fail(USER_UNAVAILABLE));
            proof.consume(user.getId(), fingerprint, now);return null;
        });
    }

    @Override
    public void purgeExpiredSignupArtifacts() {
        store.purgeExpired(Instant.now());
    }

    private void link(String userId, String provider, String subject, Instant now) {
        var current=store.findUserAccount(userId, provider);
        if (current.isPresent()) {
            if (!current.get().getProviderSubject().equals(subject)) throw fail(ACCOUNT_LINK_CONFLICT);
            return;
        }
        store.createAccount(new UserOAuthAccount(userId, provider, subject, now));
    }

    private SignupAuthentication loadProof(String raw, String caller) {
        var proof=store.lockProof(requiredHash(raw)).orElseThrow(() -> fail(PROOF_INVALID));
        proof.requireUsable(requiredHash(caller), Instant.now());
        return proof;
    }

    private SignupProgress proofProgress(SignupAuthentication p) {
        if (p.getResultUserId()!=null) return userProgress(activeUser(p.getResultUserId()), p.getExpiresAt());
        if (p.getVerifiedEmail() == null || p.getVerifiedEmail().isBlank()) throw fail(PROOF_INVALID);
        return new SignupProgress(SignupNextAction.AGREEMENTS, p.getVerifiedEmail(), null, null, p.getExpiresAt());
    }

    private User activeUser(String id) {
        User user=store.findUser(id).orElseThrow(() -> fail(USER_UNAVAILABLE));
        if (user.isWithdrawn()) throw fail(USER_UNAVAILABLE);
        return user;
    }



    private SignupProgress userProgress(User u, Instant expiresAt) {
        return new SignupProgress(u.isProfileSetupRequired()?SignupNextAction.PROFILE:SignupNextAction.COMPLETE, u.getEmail(), u.getId(), u.getProfileSetupStatus().name(), expiresAt);
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

    private void requireEnabled() {
        if (!policy.enabled()) throw fail(SIGNUP_DISABLED);
    }

    private void requireLegacy() {
        if (policy.enabled() || !policy.legacySignupEnabled()) throw fail(LEGACY_SIGNUP_DISABLED);
    }

    private SignupProofResult completedAttempt(Attempt a) {
        if (a.failure()!=null)throw fail(a.failure());
        return a.result();
    }

    private <T> T tx(Supplier<T> work) {
        try {
            return transactions.required(work);
        } catch (SignupProofException e) {
            throw fail(switch (e.getReason()) {
                case INVALID -> PROOF_INVALID;case EXPIRED -> PROOF_EXPIRED;case ALREADY_USED -> PROOF_ALREADY_USED;
                case FLOW_MISMATCH -> FLOW_MISMATCH;
            });
        }
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

    private static String token() {
        byte[] bytes=new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String frame(String value) {
        return value==null?"-1:":value.length()+":"+value;
    }
    public static String fingerprint(List<AgreementAcceptance> agreements) {
        if (agreements==null)throw fail(AGREEMENTS_REQUIRED);
        if (agreements.stream().anyMatch(a -> a==null || a.type()==null || a.version()==null))throw fail(INVALID_REQUEST);
        return hash(agreements.stream().sorted(Comparator.comparing(AgreementAcceptance::type).thenComparing(AgreementAcceptance::version).thenComparing(AgreementAcceptance::agreed))
        .map(a -> frame(a.type())+frame(a.version())+a.agreed()+";").reduce("", String::concat));
    }

    private static SignupFlowException fail(SignupFailure reason) {
        return new SignupFlowException(reason);
    }

    private record Attempt(SignupFailure failure, SignupProofResult result) {
    }

    private record ChallengeReservation(String id, Instant sentAt) {
    }
}
