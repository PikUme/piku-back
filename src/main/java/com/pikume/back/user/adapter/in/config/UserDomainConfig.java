package com.pikume.back.user.adapter.in.config;

import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.domain.service.NicknamePolicy;
import com.pikume.back.user.domain.service.PasswordPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserDomainConfig {

	@Bean
	public NicknamePolicy nicknamePolicy() {
		return new NicknamePolicy();
	}

	@Bean
	public EmailVerificationPolicy emailVerificationPolicy() {
		return new EmailVerificationPolicy();
	}

	@Bean
	public PasswordPolicy passwordPolicy() {
		return new PasswordPolicy();
	}
}
