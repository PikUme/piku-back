package com.pikume.back.user.application.port.in;

import com.pikume.back.user.application.dto.NicknameReservationResult;

public interface ReserveSignupNicknameUseCase {

	NicknameReservationResult reserve(String email, String emailVerificationToken, String nickname);
}
