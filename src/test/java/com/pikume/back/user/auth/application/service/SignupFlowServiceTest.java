package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.in.SignUpUseCase;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.domain.*;
import com.pikume.back.user.domain.*;
import com.pikume.back.user.domain.service.PasswordPolicy;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignupFlowServiceTest {
    final SignupStorePort store = mock(SignupStorePort.class);
    final SignupPolicyPort policy = mock(SignupPolicyPort.class);
    final PasswordProtectionPort passwords = mock(PasswordProtectionPort.class);
    final ResolveDefaultSignupCharacterPort character = mock(ResolveDefaultSignupCharacterPort.class);
    final SignUpUseCase legacy = mock(SignUpUseCase.class);
    final Instant now = Instant.now();
    final SignupTransactionPort tx = new SignupTransactionPort() { public <T> T required(Supplier<T> work) { return work.get(); }};
    SignupFlowService service;
    List<AgreementAcceptance> consent = List.of(new AgreementAcceptance("TERMS", "v1", true));
    @BeforeEach void setup() {
        when(policy.enabled()).thenReturn(true);
        when(policy.agreements()).thenReturn(List.of(new SignupAgreementDocument("TERMS", "v1", "actual terms", true)));
        when(character.resolveDefaultSignupCharacter()).thenReturn(9L);
        var allowed = mock(com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase.class);
        when(allowed.isEmailAllowed(anyString())).thenReturn(true);
        service = new SignupFlowService(store, tx, policy, passwords, mock(IssueVerificationEmailPort.class), character, legacy, new PasswordPolicy(), allowed);
        when(store.createUser(any())).thenAnswer(i -> { User u=i.getArgument(0); return new User("u1",u.getEmail(),u.getPassword(),u.getNickname(),u.getCharacterId()); });
    }
    void proof(SignupAuthentication p) { when(store.lockProof(anyString())).thenReturn(Optional.of(p)); }
    SignupAuthentication emailProof() { return SignupAuthentication.email(SignupFlowService.hash("proof"), SignupFlowService.hash("caller"), "new@gmail.com", "once-hashed", now); }
    @Test void socialWithoutTrustedEmailRequiresVerificationAndCreatesNoUser() {
        var result=service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","CaseSubject","x@example.com",true,false,"caller",null));
        assertThat(result.progress().nextAction()).isEqualTo(SignupNextAction.VERIFY_EMAIL);
        verify(store,never()).createUser(any());
    }
    @Test void consentStoresActualVersionedContentAndClearsPasswordHash() {
        var p=emailProof();proof(p);
        var result=service.agree(new SignupAgreementCommand("proof","caller",consent));
        assertThat(result.progress().userId()).isEqualTo("u1");
        assertThat(p.getPasswordHash()).isNull();
        verify(store).recordAgreement(argThat(a -> a.getContent().equals("actual terms") && a.isAgreed()));
        verify(passwords,never()).protect(anyString());
    }
    @Test void sameConsentReplayRecoversOnlyOriginalUser() {
        var p=emailProof();p.consume("u1",SignupFlowService.fingerprint(consent),now);proof(p);
        when(store.findUser("u1")).thenReturn(Optional.of(new User("u1","new@gmail.com","hash","tester",9L)));
        assertThat(service.agree(new SignupAgreementCommand("proof","caller",consent)).progress().userId()).isEqualTo("u1");
        verify(store,never()).createUser(any());
        assertThatThrownBy(() -> service.agree(new SignupAgreementCommand("proof","caller",List.of(new AgreementAcceptance("TERMS","v2",true)))))
            .isInstanceOf(SignupFlowException.class);
    }
    @Test void differentProofCannotLoginByMatchingEmail() {
        proof(emailProof());when(store.findUserByEmail("new@gmail.com")).thenReturn(Optional.of(new User("u2","new@gmail.com","hash","tester",9L)));
        assertThatThrownBy(() -> service.agree(new SignupAgreementCommand("proof","caller",consent)))
            .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.EMAIL_ALREADY_REGISTERED);
    }
    @Test void providerSubjectPrecedesChangedEmail() {
        when(store.findAccount("GOOGLE","Subject")).thenReturn(Optional.of(new UserOAuthAccount("u2","GOOGLE","Subject",now)));
        when(store.findUser("u2")).thenReturn(Optional.of(new User("u2","old@gmail.com",null,"tester",9L)));
        assertThat(service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","Subject","changed@gmail.com",true,true,"caller",null)).progress().userId()).isEqualTo("u2");
        verify(store,never()).findUserByEmail(anyString());
    }
    @Test void socialConsentKeepsPasswordNull() {
        proof(SignupAuthentication.social(SignupFlowService.hash("proof"),SignupFlowService.hash("caller"),"GOOGLE","Subject","new@gmail.com",now));
        service.agree(new SignupAgreementCommand("proof","caller",consent));
        verify(store).createUser(argThat(u -> u.getPassword()==null && u.isProfileSetupRequired()));
        verify(store).createAccount(any());
    }
    @Test void missingDefaultCharacterStopsConsentBeforeWrites() {
        proof(emailProof());when(character.resolveDefaultSignupCharacter()).thenThrow(new SignupFlowException(SignupFailure.DEFAULT_CHARACTER_UNAVAILABLE));
        assertThatThrownBy(() -> service.agree(new SignupAgreementCommand("proof","caller",consent))).isInstanceOf(SignupFlowException.class);
        verify(store,never()).createUser(any());verify(store,never()).recordAgreement(any());
    }
    @Test void requiredVersionMismatchStopsCreation() {
        proof(emailProof());
        assertThatThrownBy(() -> service.agree(new SignupAgreementCommand("proof","caller",List.of(new AgreementAcceptance("TERMS","stale",true)))))
            .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.AGREEMENT_VERSION_MISMATCH);
        verify(store,never()).createUser(any());
    }
    @Test void legacyProofCannotSubmitChapteredConsent() {
        proof(SignupAuthentication.legacy(SignupFlowService.hash("proof"),SignupFlowService.hash("caller"),"new@gmail.com",now));
        assertThatThrownBy(() -> service.agree(new SignupAgreementCommand("proof","caller",consent)))
            .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.FLOW_MISMATCH);
    }
    @Test void enabledChapteredFlowDisablesLegacyRegardlessOfLegacyFlag() {
        when(policy.legacySignupEnabled()).thenReturn(true);
        assertThatThrownBy(() -> service.issueLegacyProof("new@gmail.com","caller"))
            .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.LEGACY_SIGNUP_DISABLED);
        verifyNoInteractions(legacy);
    }
    @Test void automaticGmailLinkRequiresProvenanceAndKeepsPasswordAccount() {
        User existing=new User("u2","Name+tag@gmail.com","password-hash","tester",9L);
        when(store.findUserByEmail("name+tag@gmail.com")).thenReturn(Optional.of(existing));
        var command=new SocialSignupAuthenticationCommand("GOOGLE","Subject","name+tag@gmail.com",true,true,"caller",null);
        assertThatThrownBy(() -> service.authenticateSocial(command)).isInstanceOf(SignupFlowException.class)
                .extracting("reason").isEqualTo(SignupFailure.ACCOUNT_LINK_CONFLICT);
        when(policy.legacyEmailAccountsVerified()).thenReturn(true);
        assertThat(service.authenticateSocial(command).progress().userId()).isEqualTo("u2");
        verify(store).createAccount(argThat(a -> a.getUserId().equals("u2") && a.getProviderSubject().equals("Subject")));
        assertThat(existing.getPassword()).isEqualTo("password-hash");
    }
    @Test void looseDatabaseEmailMatchCannotMergePlusTagsOrDots() {
        when(policy.legacyEmailAccountsVerified()).thenReturn(true);
        when(store.findUserByEmail("name+tag@gmail.com")).thenReturn(Optional.of(new User("u2","name@gmail.com","hash","tester",9L)));
        assertThatThrownBy(() -> service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","Subject","name+tag@gmail.com",true,true,"caller",null)))
                .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.ACCOUNT_LINK_CONFLICT);
        verify(store,never()).createAccount(any());
    }
    @Test void explicitTargetMustBeActiveAndCannotReplaceAnotherSubject() {
        when(store.findUser("u2")).thenReturn(Optional.of(new User("u2","external@example.com","hash","tester",9L)));
        when(store.findUserAccount("u2","GOOGLE")).thenReturn(Optional.of(new UserOAuthAccount("u2","GOOGLE","OtherSubject",now)));
        assertThatThrownBy(() -> service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","Subject",null,false,false,"caller","u2")))
                .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.ACCOUNT_LINK_CONFLICT);
        verify(store,never()).createAccount(any());
    }
    @Test void existingLinkedUserCanResumeWhileNewSignupIsDisabled() {
        when(policy.enabled()).thenReturn(false);
        when(store.findAccount("GOOGLE","Subject")).thenReturn(Optional.of(new UserOAuthAccount("u2","GOOGLE","Subject",now)));
        when(store.findUser("u2")).thenReturn(Optional.of(new User("u2","old@gmail.com",null,"tester",9L)));
        assertThat(service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","Subject",null,false,false,"caller",null)).progress().userId()).isEqualTo("u2");
    }
    @Test void unsupportedProviderCannotIssueProofOrFindAnAccount() {
        assertThatThrownBy(() -> service.authenticateSocial(new SocialSignupAuthenticationCommand("APPLE","Subject",null,false,false,"caller",null)))
                .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.PROVIDER_NOT_SUPPORTED);
        verifyNoInteractions(store);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MISSING", "CALLER", "EXPIRED", "CODE", "EXHAUSTED", "CONSUMED"})
    void rejectedEmailChallengeDoesNotHashPassword(String rejection) {
        when(policy.maxCodeAttempts()).thenReturn(1);
        Instant issuedAt = rejection.equals("EXPIRED") ? now.minusSeconds(301) : now;
        Verification challenge = Verification.signupChallenge("challenge", "new@gmail.com",
                SignupFlowService.hash("caller"), null, issuedAt, 60);
        challenge.activateSignupCode("123456", issuedAt);
        if (rejection.equals("EXHAUSTED")) {
            challenge.validateSignup("new@gmail.com", SignupFlowService.hash("caller"), null, "000000", now, 1);
        }
        if (rejection.equals("CONSUMED")) challenge.consumeSignup(now);
        when(store.lockChallenge("challenge")).thenReturn(rejection.equals("MISSING") ? Optional.empty() : Optional.of(challenge));
        String caller = rejection.equals("CALLER") ? "other-caller" : "caller";
        String code = rejection.equals("CODE") ? "000000" : "123456";
        SignupFailure failure = switch (rejection) {
            case "EXPIRED" -> SignupFailure.CODE_EXPIRED;
            case "CODE" -> SignupFailure.CODE_MISMATCH;
            case "EXHAUSTED" -> SignupFailure.ATTEMPTS_EXHAUSTED;
            default -> SignupFailure.CHALLENGE_INVALID;
        };

        assertThatThrownBy(() -> service.authenticateEmail(new EmailSignupAuthenticationCommand(
                "challenge", "new@gmail.com", code, "Password!", caller)))
                .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(failure);

        verify(passwords, never()).protect(anyString());
        verify(store, never()).saveProof(any());
    }

    @Test
    void successfulEmailChallengeHashesOnceAfterValidationAndStoresTheHash() {
        when(policy.maxCodeAttempts()).thenReturn(1);
        Verification challenge = Verification.signupChallenge("challenge", "new@gmail.com",
                SignupFlowService.hash("caller"), null, now, 60);
        challenge.activateSignupCode("123456", now);
        when(store.lockChallenge("challenge")).thenReturn(Optional.of(challenge));
        when(passwords.protect("Password!")).thenReturn("protected-once");

        service.authenticateEmail(new EmailSignupAuthenticationCommand(
                "challenge", "new@gmail.com", "123456", "Password!", "caller"));

        var order = inOrder(store, passwords);
        order.verify(store).lockChallenge("challenge");
        order.verify(passwords).protect("Password!");
        order.verify(store).saveProof(argThat(proof -> proof.getPasswordHash().equals("protected-once")));
        verify(passwords, times(1)).protect(anyString());
        assertThat(challenge.getConsumedAt()).isNotNull();
    }
}
