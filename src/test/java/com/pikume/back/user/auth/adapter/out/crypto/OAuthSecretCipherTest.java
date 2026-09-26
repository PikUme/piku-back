package com.pikume.back.user.auth.adapter.out.crypto;

import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class OAuthSecretCipherTest {
    OAuthSecretCipher cipher() { return new OAuthSecretCipher(Base64.getEncoder().encodeToString(new byte[32]), new SecureRandom()); }
    @Test void encryptsRandomlyAndAuthenticatesContext() {
        var cipher = cipher();
        String first = cipher.encrypt("nonce", "request-1:nonce");
        String second = cipher.encrypt("nonce", "request-1:nonce");
        assertThat(first).doesNotContain("nonce").isNotEqualTo(second);
        assertThat(cipher.decrypt(first, "request-1:nonce")).isEqualTo("nonce");
        assertThatThrownBy(() -> cipher.decrypt(first, "request-2:nonce")).isInstanceOf(OAuthRequestException.class);
    }
    @Test void rejectsTamperedCiphertextAndWrongKeyLength() {
        var cipher = cipher();
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("verifier", "r:verifier"));
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "r:verifier"))
            .isInstanceOf(OAuthRequestException.class);
        assertThatThrownBy(() -> new OAuthSecretCipher(Base64.getEncoder().encodeToString(new byte[16]), new SecureRandom()))
            .isInstanceOf(OAuthRequestException.class);
    }
}
