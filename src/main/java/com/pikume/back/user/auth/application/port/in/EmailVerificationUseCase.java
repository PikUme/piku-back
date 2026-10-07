package com.pikume.back.user.auth.application.port.in;

public interface EmailVerificationUseCase {

	void sendSignUpVerificationEmail(String email);

	void verifySignUpVerificationCode(String email, String code);
}
