package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.SignupEmailVerification;
import com.pikume.back.user.auth.application.dto.SignupVerificationSent;

public interface EmailVerificationUseCase {

	SignupVerificationSent sendSignUpVerificationEmail(String email);

	SignupEmailVerification verifySignUpVerificationCode(String email, String code);
}
