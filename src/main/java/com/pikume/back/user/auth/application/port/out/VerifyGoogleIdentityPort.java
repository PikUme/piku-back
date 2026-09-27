package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.GoogleIdentity;

public interface VerifyGoogleIdentityPort {
    GoogleIdentity verifyIdToken(String clientRegistration, String idToken, String expectedNonce);
}
