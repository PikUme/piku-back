package com.pikume.back.user.auth.adapter.out.google;

import com.pikume.back.user.auth.adapter.out.config.OAuthProtocolProperties;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleOAuthProtocolPolicyTest {
    @Test void disabledByDefault() {
        var google = new GoogleOAuthProperties();
        var policy = new GoogleOAuthProtocolPolicy(google, new OAuthProtocolProperties());
        assertThatThrownBy(() -> policy.requireRegistration(OAuthAuthorizationRequest.Channel.WEB, "web"))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.DISABLED);
        assertThatThrownBy(() -> policy.requireRegistration(OAuthAuthorizationRequest.Channel.WEB, "web"))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.DISABLED);
    }
    @Test void onlyConfiguredMobileRegistrationsAreAccepted() {
        var google = new GoogleOAuthProperties();
        google.setEnabled(true); google.setWebClientId("client"); google.setWebClientSecret("secret");
        google.setWebRedirectUri("https://api.example.com/callback");
        google.setMobileRegistrations(Map.of("ios", new GoogleOAuthProperties.MobileRegistration("ios-client", null)));
        var policy = new GoogleOAuthProtocolPolicy(google, new OAuthProtocolProperties());
        assertThatCode(() -> policy.requireRegistration(OAuthAuthorizationRequest.Channel.MOBILE, "ios")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireRegistration(OAuthAuthorizationRequest.Channel.MOBILE, "web"))
            .isInstanceOf(OAuthRequestException.class);
        assertThatThrownBy(() -> policy.requireRegistration(OAuthAuthorizationRequest.Channel.MOBILE, "android"))
            .isInstanceOf(OAuthRequestException.class);
    }
}
