package com.pikume.back.notification.adapter.out.token;

import com.pikume.back.notification.application.port.out.LoadPushDeliveryTokensPort;
import com.pikume.back.notification.application.port.out.RegisterPushTokenPort;
import com.pikume.back.notification.application.port.out.RevokePushTokenPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile("!prod")
@Slf4j
public class LocalPushTokenAdapter implements
		LoadPushDeliveryTokensPort,
		RegisterPushTokenPort,
		RevokePushTokenPort {

	@Override
	public Set<String> loadPushDeliveryTokens(String userId) {
		return Set.of();
	}

	@Override
	public void registerPushToken(String userId, String token, String deviceId) {
		log.debug("event=local_fcm_token_save_requested outcome=skipped userId={}", userId);
	}

	@Override
	public void revokePushToken(String token) {
		log.debug("event=local_fcm_token_revoke_requested outcome=skipped");
	}

	@Override
	public void revokePushTokenForDevice(String userId, String deviceId) {
		log.debug("event=local_fcm_device_token_revoke_requested outcome=skipped userId={}", userId);
	}
}
