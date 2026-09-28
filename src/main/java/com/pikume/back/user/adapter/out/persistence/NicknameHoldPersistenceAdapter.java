package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.NicknameHoldPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pikume.back.user.domain.vo.Nickname;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NicknameHoldPersistenceAdapter implements NicknameHoldPort {
	private static final Duration HOLD_DURATION = Duration.ofMinutes(3);
	private final JdbcTemplate jdbc;

	@Override
	public void lockNicknameWrites() {
		jdbc.queryForObject("SELECT id FROM nickname_write_mutex WHERE id = 1 FOR UPDATE", Integer.class);
	}

	@Override
	public boolean tryAcquire(Nickname nickname, String ownerKey, Instant requestedAt) {
		lockNicknameWrites();
		jdbc.update("DELETE FROM nickname_holds WHERE expires_at <= ?", utc(requestedAt));
		if (isHeldBy(nickname, ownerKey, requestedAt)) return true;
		if (isHeld(nickname, requestedAt)) return false;
		// Only replace the previous reservation after the target is available.
		jdbc.update("DELETE FROM nickname_holds WHERE owner_key = ?", ownerKey);
		jdbc.update("INSERT INTO nickname_holds (nickname, owner_key, expires_at) VALUES (?, ?, ?)",
			nickname.value(), ownerKey, utc(requestedAt.plus(HOLD_DURATION)));
		return true;
	}

	@Override
	public boolean isHeldBy(Nickname nickname, String ownerKey, Instant checkedAt) {
		return heldUntil(nickname, ownerKey, checkedAt).isPresent();
	}

	@Override
	public boolean isHeld(Nickname nickname, Instant checkedAt) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
			"SELECT COUNT(*) > 0 FROM nickname_holds WHERE nickname = ? AND expires_at > ?",
			Boolean.class, nickname.value(), utc(checkedAt)));
	}

	@Override
	public Optional<Instant> heldUntil(Nickname nickname, String ownerKey, Instant checkedAt) {
		return jdbc.query("SELECT expires_at FROM nickname_holds WHERE nickname = ? AND owner_key = ? AND expires_at > ?",
			(row, index) -> row.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), nickname.value(), ownerKey, utc(checkedAt))
			.stream().findFirst();
	}

	private LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	@Override
	public void releaseForOwner(String ownerKey) {
		jdbc.update("DELETE FROM nickname_holds WHERE owner_key = ?", ownerKey);
	}

	@Override
	public void release(Nickname nickname, String ownerKey) {
		jdbc.update("DELETE FROM nickname_holds WHERE nickname = ? AND owner_key = ?", nickname.value(), ownerKey);
	}
}
