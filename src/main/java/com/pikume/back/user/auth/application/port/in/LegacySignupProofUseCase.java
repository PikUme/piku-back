package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.*;

public interface LegacySignupProofUseCase {
    SignupProofResult issueLegacyProof(String email, String callerBinding);
    void completeLegacy(SignUpCommand command, String proof, String callerBinding);
}
