package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import java.util.Optional;

public interface EmailVerificationStorePort {

	boolean reserve(String emailKey, String generation, String codeHash);

	boolean activate(String emailKey, String generation);

	VerificationResult verify(String emailKey, String submittedCodeHash);

	Optional<SignupEmailProof> loadProof(String emailKey);

	boolean removeProofIfVersionMatches(String emailKey, String version);

	enum VerificationResult {
		VERIFIED,
		NOT_FOUND,
		EXPIRED,
		MISMATCH,
		INACTIVE
	}
}
