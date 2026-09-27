package com.pikume.back.user.auth.domain;

import com.pikume.back.user.auth.domain.exception.SignupProofException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignupAuthenticationTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void consentProofExpiresAtTenMinutesAndCannotBeExtendedByReading() {
        var proof = SignupAuthentication.email("token-hash", "caller-hash", "member@gmail.com", "password-hash", NOW);

        proof.requireUsable("caller-hash", NOW.plusSeconds(599));

        assertThatThrownBy(() -> proof.requireUsable("caller-hash", NOW.plusSeconds(600)))
                .isInstanceOf(SignupProofException.class);
        assertThat(proof.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void differentCallerCannotUseTheProof() {
        var proof = SignupAuthentication.email("token-hash", "caller-hash", "member@gmail.com", "password-hash", NOW);

        assertThatThrownBy(() -> proof.requireUsable("another-caller", NOW))
                .isInstanceOf(SignupProofException.class);
    }

    @Test
    void consumedProofClearsPasswordAndRecoversOnlyTheSameSubmissionBeforeExpiry() {
        var proof = SignupAuthentication.email("token-hash", "caller-hash", "member@gmail.com", "password-hash", NOW);

        proof.consume("user-123", "agreements-v1", NOW.plusSeconds(1));

        assertThat(proof.getPasswordHash()).isNull();
        assertThat(proof.completedUser("agreements-v1", NOW.plusSeconds(2))).isEqualTo("user-123");
        assertThatThrownBy(() -> proof.completedUser("agreements-v2", NOW.plusSeconds(2)))
                .isInstanceOf(SignupProofException.class);
        assertThatThrownBy(() -> proof.completedUser("agreements-v1", NOW.plusSeconds(600)))
                .isInstanceOf(SignupProofException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "invalid"})
    void socialProofCannotBeCreatedWithoutAUsableProviderEmail(String email) {
        assertThatThrownBy(() -> SignupAuthentication.social("token-hash", "caller-hash", "GOOGLE", "Subject", email, NOW))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void socialEmailCannotExceedThePersistedEmailLimit() {
        assertThatThrownBy(() -> SignupAuthentication.social("token-hash", "caller-hash", "GOOGLE", "Subject", "a".repeat(246)+"@gmail.com", NOW))
                .isInstanceOf(SignupProofException.class);
    }

    @Test
    void validProviderEmailIsReadyForConsentWithoutAServiceCode() {
        var proof = SignupAuthentication.social("token-hash", "caller-hash", "GOOGLE", "CaseSensitiveSubject", "member@naver.com", NOW);

        proof.requireConsentReady();

        assertThat(proof.getVerifiedEmail()).isEqualTo("member@naver.com");
        assertThat(proof.getEmailVerificationSource()).isEqualTo("PROVIDER");
        assertThat(proof.getProviderSubject()).isEqualTo("CaseSensitiveSubject");
        assertThat(proof.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(proof.getPasswordHash()).isNull();
    }

    @Test
    void legacyCodeProofCannotSkipPasswordInChapteredConsent() {
        var proof = SignupAuthentication.legacy("token-hash", "caller-hash", "member@gmail.com", NOW);

        assertThatThrownBy(proof::requireConsentReady).isInstanceOf(SignupProofException.class);
    }
}
