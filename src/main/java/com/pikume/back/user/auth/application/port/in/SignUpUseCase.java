package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.NicknameReservationResult;

public interface SignUpUseCase {

	void signUp(SignUpCommand command);

	NicknameReservationResult reserveNickname(String nickname, String emailVerificationToken);
}
