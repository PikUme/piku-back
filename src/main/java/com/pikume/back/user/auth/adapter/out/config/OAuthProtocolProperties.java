package com.pikume.back.user.auth.adapter.out.config;

import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "signup.protocol")
public class OAuthProtocolProperties {
    private String encryptionKey;
    private int callerHourlyLimit = 20;
    private int originHourlyLimit = 100;
    private int cleanupBatchSize = 500;
    private long cleanupIntervalMs = 300_000;
    @PostConstruct public void validate() {
        if (callerHourlyLimit < 1 || originHourlyLimit < 1 || cleanupBatchSize < 1 || cleanupBatchSize > 10000
                || cleanupIntervalMs < 1000 || cleanupIntervalMs > 86_400_000)
            throw new OAuthRequestException(OAuthRequestException.Reason.CONFIGURATION);
    }
}
