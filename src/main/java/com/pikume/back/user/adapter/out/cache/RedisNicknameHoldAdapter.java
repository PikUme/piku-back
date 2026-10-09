package com.pikume.back.user.adapter.out.cache;

import com.pikume.back.user.application.dto.NicknameHoldSnapshot;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.application.port.out.NicknameHoldPort;
import com.pikume.back.user.application.port.out.NicknameIdentityPort;
import com.pikume.back.user.application.port.out.NicknameReservationStorePort;
import com.pikume.back.user.domain.vo.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RedisNicknameHoldAdapter implements NicknameHoldPort {

	private final NicknameReservationStorePort reservations;
	private final NicknameIdentityPort identities;

	@Override
	public boolean tryAcquire(Nickname nickname, String userId, Instant requestedAt) {
		try {
			reservations.reserve(ownerKey(userId), identities.keyFor(nickname), nickname.value());
			return true;
		} catch (NicknameReservationConflictException exception) {
			return false;
		}
	}

	@Override
	public boolean isHeldBy(Nickname nickname, String userId, Instant checkedAt) {
		return reservations.isHeldBy(identities.keyFor(nickname), ownerKey(userId));
	}

	@Override
	public void release(Nickname nickname, String userId) {
		loadForOwner(userId).filter(hold -> hold.nicknameKey().equals(identities.keyFor(nickname)))
				.ifPresent(hold -> reservations.releaseIfVersionMatches(ownerKey(userId), hold.nicknameKey(), hold.version()));
	}

	@Override
	public Optional<NicknameHoldSnapshot> loadForOwner(String userId) {
		return reservations.load(ownerKey(userId)).map(hold -> new NicknameHoldSnapshot(
				hold.nickname(), hold.nicknameKey(), hold.version()));
	}

	@Override
	public boolean releaseIfVersionMatches(String userId, NicknameHoldSnapshot hold) {
		return reservations.releaseIfVersionMatches(ownerKey(userId), hold.nicknameKey(), hold.version());
	}

	private static String ownerKey(String userId) {
		return "user:" + userId;
	}
}
