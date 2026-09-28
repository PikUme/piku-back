package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.vo.Nickname;
import java.time.Instant;
import java.util.Optional;

public interface NicknameHoldPort {

	/** Serialize nickname availability, reservations, and account writes until transaction completion.
	 * Acquire after an email verification row, and before user rows or nickname availability reads.
	 * Owners are user IDs for members and email:{verification UUID} for guests. */
	void lockNicknameWrites();

	boolean tryAcquire(Nickname nickname, String ownerKey, Instant requestedAt);

	boolean isHeldBy(Nickname nickname, String ownerKey, Instant checkedAt);

	boolean isHeld(Nickname nickname, Instant checkedAt);

	Optional<Instant> heldUntil(Nickname nickname, String ownerKey, Instant checkedAt);

	void release(Nickname nickname, String ownerKey);

	void releaseForOwner(String ownerKey);
}
