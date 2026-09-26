package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.SignupAgreementDocument;
import java.util.List;
public interface SignupPolicyPort {
    boolean enabled();
    boolean legacySignupEnabled();
    boolean legacyEmailAccountsVerified();
    List<SignupAgreementDocument> agreements();
    int maxCodeAttempts();
    int resendSeconds();
    int emailHourlyLimit();
    int originHourlyLimit();
}
