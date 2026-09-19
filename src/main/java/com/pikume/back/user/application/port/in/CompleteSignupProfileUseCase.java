package com.pikume.back.user.application.port.in;

import com.pikume.back.user.application.dto.SignupProfileResult;

public interface CompleteSignupProfileUseCase {
	SignupProfileResult completeSignupProfile(String userId, String nickname, Long characterId);
}
