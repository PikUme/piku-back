package com.pikume.back.user.auth.adapter.out.crypto;
import com.pikume.back.user.auth.adapter.out.config.OAuthProtocolCryptoConfiguration;
import com.pikume.back.user.auth.adapter.out.config.OAuthProtocolProperties;
import com.pikume.back.user.auth.adapter.out.google.GoogleOAuthProperties;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OAuthProtocolCryptoConfigurationTest {
    @Test void disabledGoogleNeedsNoEncryptionKey() {
        var secrets = new OAuthProtocolCryptoConfiguration().oauthRequestSecretPort(new OAuthProtocolProperties(), new GoogleOAuthProperties());
        assertThatThrownBy(() -> secrets.encrypt("nonce", "context"))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.DISABLED);
    }
    @Test void enablingGoogleRejectsMissingEncryptionKeyImmediately() {
        var google = new GoogleOAuthProperties(); google.setEnabled(true);
        assertThatThrownBy(() -> new OAuthProtocolCryptoConfiguration().oauthRequestSecretPort(new OAuthProtocolProperties(), google))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.CONFIGURATION);
    }
}
