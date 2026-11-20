package com.pikume.back.global.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("prod")
@RequiredArgsConstructor
class ProductionMailStartupValidator {

	private final Environment environment;

	@PostConstruct
	void validate() {
		requireSetting("outbound-email.api-key");
		requireSetting("outbound-email.from-address");
		requireSetting("outbound-email.feedback-recipient");
	}

	private void requireSetting(String property) {
		if (!StringUtils.hasText(environment.getProperty(property))) {
			throw new IllegalStateException(property + " 운영 메일 설정은 필수입니다.");
		}
	}
}
