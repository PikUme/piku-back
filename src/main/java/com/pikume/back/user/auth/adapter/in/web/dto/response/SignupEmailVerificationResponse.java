package com.pikume.back.user.auth.adapter.in.web.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record SignupEmailVerificationResponse(
		String message,
		String emailVerificationToken,
		@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
		LocalDateTime expiresAt) {
}
