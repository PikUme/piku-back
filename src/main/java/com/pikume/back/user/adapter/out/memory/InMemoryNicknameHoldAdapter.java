package com.pikume.back.user.adapter.out.memory;

import com.pikume.back.user.application.port.out.NicknameHoldPort;
import com.pikume.back.user.domain.service.NicknamePolicy;
import com.pikume.back.user.domain.vo.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class InMemoryNicknameHoldAdapter implements NicknameHoldPort {

	private final NicknamePolicy nicknamePolicy;
	private final ConcurrentHashMap<Nickname, HoldEntry> holds = new ConcurrentHashMap<>();

	@Override
	public boolean tryAcquire(Nickname nickname, String userId, Instant requestedAt) {
		HoldEntry current = holds.compute(nickname, (key, hold) -> {
			if (hold == null || nicknamePolicy.isHoldExpired(hold.heldAt(), requestedAt)) {
				return new HoldEntry(userId, requestedAt);
			}
			return hold;
		});
		return current.userId().equals(userId);
	}

	@Override
	public boolean isHeldBy(Nickname nickname, String userId, Instant checkedAt) {
		HoldEntry current = holds.computeIfPresent(nickname, (key, hold) ->
				nicknamePolicy.isHoldExpired(hold.heldAt(), checkedAt) ? null : hold);
		return current != null && current.userId().equals(userId);
	}

	@Override
	public void release(Nickname nickname, String userId) {
		holds.computeIfPresent(nickname, (key, hold) -> hold.userId().equals(userId) ? null : hold);
	}

	private record HoldEntry(String userId, Instant heldAt) {
	}
}
