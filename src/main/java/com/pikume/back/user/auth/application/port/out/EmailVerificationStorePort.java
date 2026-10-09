package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationStorePort {

	ReservationResult reserve(String emailKey, String generation, String codeHash, String requestId);

	Optional<LocalDateTime> activate(String emailKey, String generation);

	VerificationResult verify(String emailKey, String submittedCodeHash, String version, String tokenHash);

	Optional<SignupEmailProof> loadProof(String emailKey, String tokenHash);

	boolean removeProofIfVersionMatches(String emailKey, String version);

	record ReservationResult(ReservationStatus status, LocalDateTime expiresAt, LocalDateTime resendAvailableAt) {
	}

	record VerificationResult(VerificationStatus status, SignupEmailProof proof, LocalDateTime retryAt) {
	}

	enum ReservationStatus {
		RESERVED,
		RATE_LIMITED
	}

	enum VerificationStatus {
		VERIFIED,
		NOT_FOUND,
		EXPIRED,
		MISMATCH,
		INACTIVE,
		ATTEMPTS_EXHAUSTED
	}
}
