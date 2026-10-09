package com.pikume.back.user.application.dto;

import java.time.LocalDateTime;

public record NicknameReservationResult(
		String nickname, String nicknameKey, String version, LocalDateTime expiresAt) {
}
