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

	@PostConstruct
	public void validate() {
		if (maxCodeAttempts < 1 || resendSeconds < 1 || emailHourlyLimit < 1) {
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
}
