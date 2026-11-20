package com.pikume.back.global.config;

import com.pikume.back.global.adapter.out.email.MailpitEmailAdapter;
import com.pikume.back.global.adapter.out.email.ResendEmailAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
class OutboundEmailConfiguration {
	private static final String RESEND_URL = "https://api.resend.com/emails";

	@Bean
	@Profile("!prod")
	MailpitEmailAdapter mailpitEmailAdapter(
			@Value("${outbound-email.mailpit-url:}") String url) {
		return new MailpitEmailAdapter(OutboundEmailClientFactory.create(url, null));
	}

	@Bean
	@Profile("prod")
	ResendEmailAdapter resendEmailAdapter(@Value("${outbound-email.api-key:}") String apiKey) {
		return new ResendEmailAdapter(OutboundEmailClientFactory.create(RESEND_URL, apiKey));
	}
}
