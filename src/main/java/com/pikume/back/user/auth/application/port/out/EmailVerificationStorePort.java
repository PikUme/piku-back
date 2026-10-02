package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.EmailVerificationReservation;
import com.pikume.back.user.auth.application.dto.EmailVerificationResult;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import java.time.LocalDateTime;

public interface EmailVerificationStorePort {
	EmailVerificationReservation prepare(String emailKey, String generation, String codeHash,
			int hourlyLimit, int resendSeconds);
	boolean activate(String emailKey, String generation, String codeHash);
	EmailVerificationAttempt verify(String emailKey, String submittedCodeHash, String tokenHash, String rawToken, int maxAttempts);
	boolean isTokenValid(String emailKey, String tokenHash);
	boolean removeToken(String emailKey, String tokenHash);

	record EmailVerificationAttempt(EmailVerificationFailure failure, EmailVerificationResult result) {}
}
