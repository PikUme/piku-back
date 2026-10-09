package com.pikume.back.user.adapter.in.web.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record SignupNicknameReservationResponse(
		String nickname,
		@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime expiresAt) {
}
