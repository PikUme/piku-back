package com.pikume.back.user.auth.adapter.out.config;

import com.pikume.back.user.auth.application.port.out.SignupPolicyPort;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "signup")
@Data
public class SignupProperties implements SignupPolicyPort {
    private int maxCodeAttempts = 5;
    private int resendSeconds = 60;
    private int emailHourlyLimit = 5;
    private int originHourlyLimit = 30;
    @PostConstruct public void validate() {
        if (maxCodeAttempts < 1 || resendSeconds < 1 || emailHourlyLimit < 1 || originHourlyLimit < 1)
        throw new IllegalStateException("Invalid signup email limits");
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
