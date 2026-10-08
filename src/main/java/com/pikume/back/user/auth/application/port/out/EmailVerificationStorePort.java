package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationStorePort {

	boolean reserve(String emailKey, String generation, String codeHash);

	Optional<LocalDateTime> activate(String emailKey, String generation);

	VerificationResult verify(String emailKey, String submittedCodeHash, String version, String tokenHash);

	Optional<SignupEmailProof> loadProof(String emailKey, String tokenHash);

	boolean removeProofIfVersionMatches(String emailKey, String version);

	record VerificationResult(VerificationStatus status, SignupEmailProof proof) {
	}

	enum VerificationStatus {
		VERIFIED,
		NOT_FOUND,
		EXPIRED,
		MISMATCH,
		INACTIVE
	}
}
