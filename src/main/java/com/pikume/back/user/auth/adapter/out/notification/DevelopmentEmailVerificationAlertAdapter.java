package com.pikume.back.user.auth.adapter.out.notification;

import com.pikume.back.user.auth.application.port.out.EmailVerificationOperationsAlertPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class DevelopmentEmailVerificationAlertAdapter implements EmailVerificationOperationsAlertPort {

	@Override
	public void signupTokenCleanupFailed(String errorType) {
		// 개발·테스트 환경은 실제 운영 채널로 장애 알림을 보내지 않는다.
	}
}
