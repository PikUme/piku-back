package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.VerifyEmailCommand;

public interface VerifyEmailUseCase {

	void sendPasswordResetVerificationEmail(String email);

	void verifyCode(VerifyEmailCommand command);
}
