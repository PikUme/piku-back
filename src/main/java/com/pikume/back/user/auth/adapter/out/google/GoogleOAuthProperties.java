package com.pikume.back.user.auth.adapter.out.google;

import com.pikume.back.user.auth.application.exception.GoogleAuthenticationException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.pikume.back.user.auth.application.exception.GoogleAuthenticationException.Reason.CONFIGURATION;
import static com.pikume.back.user.auth.application.exception.GoogleAuthenticationException.Reason.DISABLED;
import static com.pikume.back.user.auth.application.exception.GoogleAuthenticationException.Reason.INVALID_IDENTITY;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "signup.google")
public class GoogleOAuthProperties {
    private boolean enabled;
    private String webClientId;
    private String webClientSecret;
    private String webRedirectUri;
    private Map<String, MobileRegistration> mobileRegistrations = new LinkedHashMap<>();

    public record MobileRegistration(String audience, String authorizedParty) {}

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (blank(webClientId) || blank(webClientSecret) || blank(webRedirectUri) || mobileRegistrations == null) {
            throw new GoogleAuthenticationException(CONFIGURATION);
        }
        try {
            URI redirect = URI.create(webRedirectUri);
            boolean localHttp = "http".equals(redirect.getScheme()) &&
                ("localhost".equals(redirect.getHost()) || "127.0.0.1".equals(redirect.getHost()) || "[::1]".equals(redirect.getHost()));
            if ((!"https".equals(redirect.getScheme()) && !localHttp) || blank(redirect.getHost()) ||
                redirect.getRawUserInfo() != null || redirect.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new GoogleAuthenticationException(CONFIGURATION);
        }
        mobileRegistrations.forEach((name, registration) -> {
            if (name == null || !name.matches("[a-z][a-z0-9_-]{0,63}") || "web".equals(name) ||
                registration == null || blank(registration.audience()) ||
                (registration.authorizedParty() != null && blank(registration.authorizedParty()))) {
                throw new GoogleAuthenticationException(CONFIGURATION);
            }
        });
    }

    MobileRegistration registration(String name) {
        requireEnabled();
        if ("web".equals(name)) return new MobileRegistration(webClientId, null);
        MobileRegistration registration = mobileRegistrations.get(name);
        if (registration == null) throw new GoogleAuthenticationException(INVALID_IDENTITY);
        return registration;
    }

    void requireEnabled() {
        if (!enabled) throw new GoogleAuthenticationException(DISABLED);
        validate();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
