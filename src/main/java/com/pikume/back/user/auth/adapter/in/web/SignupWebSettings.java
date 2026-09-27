package com.pikume.back.user.auth.adapter.in.web;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import static com.pikume.back.user.auth.adapter.in.web.SignupWebException.Reason.CONFIGURATION;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix="signup.web")
public class SignupWebSettings {
    private String completionUri;
    @Value("${signup.google.enabled:false}") private boolean googleEnabled;

    @PostConstruct
    public void validate() {
        if (!googleEnabled) return;
        try {
            URI uri = URI.create(completionUri);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) { throw new SignupWebException(CONFIGURATION); }
    }

    public URI completion() {
        if (completionUri == null || completionUri.isBlank()) throw new SignupWebException(CONFIGURATION);
        return URI.create(completionUri);
    }
}
