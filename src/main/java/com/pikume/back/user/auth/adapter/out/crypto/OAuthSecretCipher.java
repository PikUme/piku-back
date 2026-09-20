package com.pikume.back.user.auth.adapter.out.crypto;

import com.pikume.back.user.auth.application.port.out.OAuthRequestSecretPort;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import static com.pikume.back.user.auth.domain.exception.OAuthRequestException.Reason.CONFIGURATION;

/** AES-256-GCM; fresh 96-bit IV and request/field AAD prevent value substitution. */
public final class OAuthSecretCipher implements OAuthRequestSecretPort {
    private final SecretKeySpec key;
    private final SecureRandom random;
    public OAuthSecretCipher(String encodedKey, SecureRandom random) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey);
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
            this.random = random;
        } catch (IllegalArgumentException exception) { throw new OAuthRequestException(CONFIGURATION); }
    }
    public String encrypt(String plaintext, String context) {
        byte[] iv = new byte[12]; random.nextBytes(iv);
        try {
            var cipher = initialize(Cipher.ENCRYPT_MODE, iv, context);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) { throw new OAuthRequestException(CONFIGURATION); }
    }
    public String decrypt(String ciphertext, String context) {
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            if (payload.length < 28) throw new IllegalArgumentException();
            byte[] iv = new byte[12]; System.arraycopy(payload, 0, iv, 0, 12);
            var cipher = initialize(Cipher.DECRYPT_MODE, iv, context);
            return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) { throw new OAuthRequestException(CONFIGURATION); }
    }
    private Cipher initialize(int mode, byte[] iv, String context) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
