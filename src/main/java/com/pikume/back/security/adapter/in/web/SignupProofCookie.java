package com.pikume.back.security.adapter.in.web;

import org.springframework.http.ResponseCookie;

/** Shared HTTP credential contract for signup and ordinary session boundaries. */
public final class SignupProofCookie {
    private SignupProofCookie() {}

    public static ResponseCookie expired() {
        return ResponseCookie.from(AuthWebConstants.SIGNUP_PROOF_COOKIE, "")
            .httpOnly(true).secure(true).path("/").sameSite("Lax").maxAge(0).build();
    }
}
