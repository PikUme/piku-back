package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.vo.Nickname;

import java.time.Instant;

public interface NicknameHoldPort {

	boolean tryAcquire(Nickname nickname, String userId, Instant requestedAt);

	boolean isHeldBy(Nickname nickname, String userId, Instant checkedAt);

	void release(Nickname nickname, String userId);
}
