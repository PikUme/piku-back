package com.pikume.back.user.auth.adapter.out.config;

import com.pikume.back.user.auth.adapter.out.crypto.OAuthSecretCipher;
import com.pikume.back.user.auth.adapter.out.google.GoogleOAuthProperties;
import com.pikume.back.user.auth.application.port.out.OAuthRequestSecretPort;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.security.SecureRandom;

@Configuration
public class OAuthProtocolCryptoConfiguration {
    @Bean public OAuthRequestSecretPort oauthRequestSecretPort(OAuthProtocolProperties protocol, GoogleOAuthProperties google) {
        // Google enablement validates the key even before the signup feature flag is turned on.
        if (google.isEnabled()) return new OAuthSecretCipher(protocol.getEncryptionKey(), new SecureRandom());
        return new OAuthRequestSecretPort() {
            public String encrypt(String plaintext, String context) { throw disabled(); }
            public String decrypt(String ciphertext, String context) { throw disabled(); }
            private OAuthRequestException disabled() { return new OAuthRequestException(OAuthRequestException.Reason.DISABLED); }
        };
    }
}
