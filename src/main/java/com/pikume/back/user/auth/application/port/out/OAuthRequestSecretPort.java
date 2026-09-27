package com.pikume.back.user.auth.application.port.out;
public interface OAuthRequestSecretPort {
    String encrypt(String plaintext, String context);
    String decrypt(String ciphertext, String context);
}
