package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.SignUpCommand;

public interface SignUpUseCase {

	void signUp(SignUpCommand command);
}
