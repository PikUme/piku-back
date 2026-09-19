package com.pikume.back.user.application.port.in;

import com.pikume.back.user.application.dto.UserAccessView;

import java.util.Optional;

public interface QueryUserAccessUseCase {

	Optional<UserAccessView> queryUserAccess(String userId);
}
