package com.pikume.back.user.domain.service;

import java.time.Duration;
import java.time.Instant;

/**
 * 닉네임 점유/검증 도메인 규칙을 담당하는 Domain Service.
 * 닉네임 점유 만료 시간, 사용자 확인 등의 비즈니스 규칙을 캡슐화합니다.
 */
public class NicknamePolicy {

	private static final Duration HOLD_DURATION = Duration.ofMinutes(3);

	/**
	 * 닉네임 점유가 만료되었는지 확인합니다.
	 *
	 * @param holdTimestamp 점유 시작 시각
	 * @param currentTime 현재 시각
	 * @return 만료 여부
	 */
	public boolean isHoldExpired(Instant holdTimestamp, Instant currentTime) {
		return Duration.between(holdTimestamp, currentTime).compareTo(HOLD_DURATION) > 0;
	}

	/**
	 * 점유 지속 시간(밀리초)을 반환합니다.
	 *
	 * @return 점유 지속 시간
	 */
	public Duration holdDuration() {
		return HOLD_DURATION;
	}
}
