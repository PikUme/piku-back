package com.pikume.back.user.auth.application.port.out;

public interface BuildGoogleAuthorizationUrlPort {
    String authorizationUrl(String state, String nonce, String codeChallenge);
}
