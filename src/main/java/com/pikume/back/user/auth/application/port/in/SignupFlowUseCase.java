package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.*;

public interface SignupFlowUseCase {
    EmailSignupChallengeResult sendEmailCode(EmailSignupChallengeCommand command);
    SignupProofResult authenticateEmail(EmailSignupAuthenticationCommand command);
    SignupProofResult verifySocialEmail(SocialSignupEmailCommand command);
    SignupProofResult agree(SignupAgreementCommand command);
    SignupProgress progress(String proof, String callerBinding);
    SignupProofResult authenticateSocial(SocialSignupAuthenticationCommand command);
}
