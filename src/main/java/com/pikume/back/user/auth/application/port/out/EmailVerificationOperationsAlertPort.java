package com.pikume.back.user.auth.application.port.out;

public interface EmailVerificationOperationsAlertPort {

	void signupProofCleanupFailed(String errorType);
}
