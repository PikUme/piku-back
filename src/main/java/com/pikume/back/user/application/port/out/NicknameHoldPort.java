package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.vo.Nickname;
import java.time.Instant;
import java.util.Optional;

public interface NicknameHoldPort {

	/** Serialize nickname availability, reservations, and account writes until transaction completion.
	 * Always acquire before locking a user or reading nickname availability. */
	void lockNicknameWrites();

	boolean tryAcquire(Nickname nickname, String userId, Instant requestedAt);

	boolean isHeldBy(Nickname nickname, String userId, Instant checkedAt);

	boolean isHeld(Nickname nickname, Instant checkedAt);

	Optional<Instant> heldUntil(Nickname nickname, String userId, Instant checkedAt);

	void release(Nickname nickname, String userId);

	void releaseForUser(String userId);
}
