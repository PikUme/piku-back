package com.pikume.back.user.auth.adapter.out.config;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SignupPropertiesTest {
    @Test void disabledDefaultsPermitLegacyButNeverAssumeEmailProvenance() {
        var p=new SignupProperties();p.validate();
        assertThat(p.enabled()).isFalse();assertThat(p.legacySignupEnabled()).isTrue();assertThat(p.legacyEmailAccountsVerified()).isFalse();
    }
    @Test void enablingWithoutActualDocumentsFailsAndNeverLeavesLegacyBypass() {
        var p=new SignupProperties();p.setEnabled(true);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
        assertThat(p.legacySignupEnabled()).isFalse();
    }
    @Test void cleanupCannotBeConfiguredToExceedTwentyFourHours() {
        var p=new SignupProperties();p.setCleanupIntervalMs(86_400_001);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }
}
