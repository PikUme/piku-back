package com.pikume.back.user.auth.adapter.out.config;

import com.pikume.back.user.auth.application.dto.SignupAgreementDocument;
import com.pikume.back.user.auth.application.port.out.SignupPolicyPort;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "signup")
@Data
public class SignupProperties implements SignupPolicyPort {
    private boolean enabled = false;
    private boolean legacySignupEnabled = true;
    private boolean legacyEmailAccountsVerified = false;
    private List<Agreement> agreements = new ArrayList<>();
    private int maxCodeAttempts = 5;
    private int resendSeconds = 60;
    private int emailHourlyLimit = 5;
    private int originHourlyLimit = 30;
    private long cleanupIntervalMs = 3_600_000;
    @Data public static class Agreement {
        private String type;
        private String version;
        private String content;
        private boolean required = true;
    }
    @PostConstruct public void validate() {
        if (maxCodeAttempts < 1 || resendSeconds < 1 || emailHourlyLimit < 1 || originHourlyLimit < 1
        || cleanupIntervalMs < 1000 || cleanupIntervalMs > 86_400_000)
        throw new IllegalStateException("Invalid signup limits or cleanup interval");
        if (!enabled) return;
        if (agreements == null || agreements.isEmpty() || agreements.stream().noneMatch(Agreement::isRequired))
        throw new IllegalStateException("Enabled signup requires actual versioned agreement documents");
        Set<String> types = new HashSet<>();
        for (Agreement a : agreements) {
            if (a.type == null || a.type.isBlank() || a.type.length()>50 || a.version == null || a.version.isBlank()
            || a.version.length()>50 || a.content == null || a.content.isBlank() || !types.add(a.type))
            throw new IllegalStateException("Signup agreement type, version and content must be configured uniquely");
        }
    }
    public boolean enabled() {
        return enabled;
    }
    public boolean legacySignupEnabled() {
        return !enabled && legacySignupEnabled;
    }
    public boolean legacyEmailAccountsVerified() {
        return legacyEmailAccountsVerified;
    }
    public List<SignupAgreementDocument> agreements() {
        return agreements.stream().map(a -> new SignupAgreementDocument(a.type, a.version, a.content, a.required)).toList();
    }
    public int maxCodeAttempts() {
        return maxCodeAttempts;
    }
    public int resendSeconds() {
        return resendSeconds;
    }
    public int emailHourlyLimit() {
        return emailHourlyLimit;
    }
    public int originHourlyLimit() {
        return originHourlyLimit;
    }
}
