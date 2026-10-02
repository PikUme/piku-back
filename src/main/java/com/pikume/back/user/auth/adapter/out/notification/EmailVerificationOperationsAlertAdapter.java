package com.pikume.back.user.auth.adapter.out.notification;

import com.pikume.back.global.notification.DiscordWebhookService;
import com.pikume.back.global.notification.dto.OperationalAlert;
import com.pikume.back.user.auth.application.port.out.EmailVerificationOperationsAlertPort;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class EmailVerificationOperationsAlertAdapter implements EmailVerificationOperationsAlertPort {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final DiscordWebhookService discord;
	private final String environment;

	public EmailVerificationOperationsAlertAdapter(DiscordWebhookService discord,
			@Value("${spring.profiles.active:prod}") String environment) {
		this.discord = discord;
		this.environment = environment;
	}

	@Override
	public void signupTokenCleanupFailed(String errorType) {
		discord.sendOperationalAlert(new OperationalAlert(
				"회원가입 이메일 인증 Redis 장애", environment, LocalDateTime.now(KST),
				"/api/auth/signup", "POST", "signup_token_cleanup", errorType, 201));
	}
}
