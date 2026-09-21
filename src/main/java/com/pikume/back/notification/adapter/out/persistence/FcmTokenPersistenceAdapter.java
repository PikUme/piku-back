package com.pikume.back.notification.adapter.out.persistence;

import com.pikume.back.notification.application.port.out.LoadPushDeliveryTokensPort;
import com.pikume.back.notification.application.port.out.RegisterPushTokenPort;
import com.pikume.back.notification.application.port.out.RevokePushTokenPort;
import com.pikume.back.notification.domain.FcmToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.NonUniqueResultException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class FcmTokenPersistenceAdapter implements
		LoadPushDeliveryTokensPort,
		RegisterPushTokenPort,
		RevokePushTokenPort {

	private final FcmTokenJpaRepository fcmTokenJpaRepository;

	@Override
	public Set<String> loadPushDeliveryTokens(String userId) {
		return fcmTokenJpaRepository.findAllByUserId(userId).stream()
				.map(FcmToken::getToken)
				.collect(Collectors.toSet());
	}

	@Override
	@Transactional
	public void registerPushToken(String userId, String token, String deviceId) {
		try {
			fcmTokenJpaRepository.findByUserIdAndDeviceId(userId, deviceId).ifPresentOrElse(
					existing -> existing.updateToken(token),
					() -> fcmTokenJpaRepository.save(new FcmToken(userId, token, deviceId)));
		} catch (IncorrectResultSizeDataAccessException | NonUniqueResultException e) {
			log.error("event=fcm_token_duplicate_detected outcome=failed userId={}", userId);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void revokePushToken(String token) {
		fcmTokenJpaRepository.deleteByToken(token);
		log.info("event=fcm_token_deleted outcome=success");
	}

	@Override
	@Transactional
	public void revokePushTokenForDevice(String userId, String deviceId) {
		fcmTokenJpaRepository.deleteByUserIdAndDeviceId(userId, deviceId);
		log.info("event=fcm_device_token_deleted outcome=success userId={}", userId);
	}
}
