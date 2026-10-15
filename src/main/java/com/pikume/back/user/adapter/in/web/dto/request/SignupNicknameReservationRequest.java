package com.pikume.back.user.adapter.in.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SignupNicknameReservationRequest(
		@NotBlank(message = "이메일은 필수 값입니다.") String email,
		@NotBlank(message = "이메일 인증 토큰은 필수 값입니다.") String emailVerificationToken,
		@NotBlank(message = "닉네임은 필수 값입니다.") String nickname) {
}
