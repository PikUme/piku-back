package com.pikume.back.user.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NicknamePolicy Domain Service")
class NicknamePolicyTest {

	private final NicknamePolicy nicknamePolicy = new NicknamePolicy();

	@Test
	@DisplayName("점유 만료 시간 이내이면 만료되지 않았다")
	void holdNotExpired() {
		Instant holdTimestamp = Instant.parse("2026-07-12T00:00:00Z");
		Instant now = holdTimestamp.plusSeconds(60);

		assertThat(nicknamePolicy.isHoldExpired(holdTimestamp, now)).isFalse();
	}

	@Test
	@DisplayName("점유 만료 시간을 초과하면 만료된다")
	void holdExpired() {
		Instant holdTimestamp = Instant.parse("2026-07-12T00:00:00Z");
		Instant now = holdTimestamp.plusSeconds(200);

		assertThat(nicknamePolicy.isHoldExpired(holdTimestamp, now)).isTrue();
	}

	@Test
	@DisplayName("정확히 3분이면 만료되지 않았다")
	void holdExactlyAtBoundary() {
		Instant holdTimestamp = Instant.parse("2026-07-12T00:00:00Z");
		Instant now = holdTimestamp.plusSeconds(180);

		assertThat(nicknamePolicy.isHoldExpired(holdTimestamp, now)).isFalse();
	}

	@Test
	@DisplayName("점유 지속 시간은 3분(180,000ms)이다")
	void holdDuration() {
		assertThat(nicknamePolicy.holdDuration()).isEqualTo(java.time.Duration.ofMinutes(3));
	}
}
