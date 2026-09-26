package com.pikume.back.user.application.port.in;

import com.pikume.back.user.application.dto.SignupNicknameReservation;

public interface ReserveSignupNicknameUseCase {
	SignupNicknameReservation reserveSignupNickname(String userId, String nickname);
}
