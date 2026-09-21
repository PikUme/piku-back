package com.pikume.back.notification.application.service;

import com.pikume.back.notification.application.port.in.RegisterPushTokenUseCase;
import com.pikume.back.notification.application.port.in.RevokePushTokenUseCase;
import com.pikume.back.notification.application.port.out.RegisterPushTokenPort;
import com.pikume.back.notification.application.port.out.RevokePushTokenPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushTokenService implements RegisterPushTokenUseCase, RevokePushTokenUseCase {

	private final RegisterPushTokenPort registerPushTokenPort;
	private final RevokePushTokenPort revokePushTokenPort;

	@Override
	public void registerPushToken(String userId, String token, String deviceId) {
		log.debug("event=fcm_token_save_requested userId={}", userId);
		registerPushTokenPort.registerPushToken(userId, token, deviceId);
	}

	@Override
	public void revokePushTokenForDevice(String userId, String deviceId) {
		log.debug("event=fcm_device_token_revoke_requested userId={}", userId);
		revokePushTokenPort.revokePushTokenForDevice(userId, deviceId);
	}
}
