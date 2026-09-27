package com.pikume.back.user.application.port.out;

import com.pikume.back.user.application.dto.UserAccessView;

import java.util.Optional;

public interface QueryUserAccessPort {

	Optional<UserAccessView> queryUserAccess(String userId);
}
