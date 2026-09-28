package com.pikume.back.user.auth.adapter.out.config;

import com.pikume.back.user.auth.application.port.out.EmailVerificationPolicyPort;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "email-verification")
@Getter
@Setter
public class EmailVerificationProperties implements EmailVerificationPolicyPort {

	private int maxCodeAttempts = 5;
	private int resendSeconds = 60;
	private int emailHourlyLimit = 5;
	private int originHourlyLimit = 30;
	private long cleanupIntervalMs = 3_600_000;

	@PostConstruct
	public void validate() {
		if (maxCodeAttempts < 1 || resendSeconds < 1 || emailHourlyLimit < 1 || originHourlyLimit < 1
				|| cleanupIntervalMs < 1000 || cleanupIntervalMs > 86_400_000) {
			throw new IllegalStateException("Invalid email verification limits");
		}
	}

	@Override
	public int maxCodeAttempts() {
		return maxCodeAttempts;
	}

	@Override
	public int resendSeconds() {
		return resendSeconds;
	}

	@Override
	public int emailHourlyLimit() {
		return emailHourlyLimit;
	}

	@Override
	public int originHourlyLimit() {
		return originHourlyLimit;
	}
}
