package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.SignupEmailVerification;
import java.time.LocalDateTime;

public interface EmailVerificationUseCase {

	LocalDateTime sendSignUpVerificationEmail(String email);

	SignupEmailVerification verifySignUpVerificationCode(String email, String code);
}
