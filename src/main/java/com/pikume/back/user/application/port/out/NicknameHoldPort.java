package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.vo.Nickname;
import com.pikume.back.user.application.dto.NicknameHoldSnapshot;
import java.util.Optional;

import java.time.Instant;

public interface NicknameHoldPort {

	boolean tryAcquire(Nickname nickname, String userId, Instant requestedAt);

	boolean isHeldBy(Nickname nickname, String userId, Instant checkedAt);

	void release(Nickname nickname, String userId);

	Optional<NicknameHoldSnapshot> loadForOwner(String userId);

	boolean releaseIfVersionMatches(String userId, NicknameHoldSnapshot hold);
}
