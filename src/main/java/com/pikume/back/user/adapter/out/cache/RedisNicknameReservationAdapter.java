package com.pikume.back.user.adapter.out.cache;

import com.pikume.back.user.application.dto.NicknameReservationResult;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.application.exception.NicknameReservationUnavailableException;
import com.pikume.back.user.application.port.out.NicknameReservationStorePort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RedisNicknameReservationAdapter implements NicknameReservationStorePort {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final long TTL_MILLIS = 600_000L;
	private static final DefaultRedisScript<String> RESERVE = stringScript("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			local owner = ARGV[1]
			local nickname = ARGV[2]
			local version = ARGV[3]
			local displayName = ARGV[4]
			local expectedOldKey = ARGV[5]
			local oldVersion = redis.call('HGET', KEYS[2], 'version')
			local oldKey = redis.call('HGET', KEYS[2], 'nicknameKey')
			local targetExists = redis.call('EXISTS', KEYS[1])
			local targetOwner = redis.call('HGET', KEYS[1], 'ownerKey')
		if targetExists == 1 and (not targetOwner or targetOwner == '') then return 'INCONSISTENT' end
		if targetOwner then
			local targetNicknameKey = redis.call('HGET', KEYS[1], 'nicknameKey')
			local targetVersion = redis.call('HGET', KEYS[1], 'version')
			local targetDisplayName = redis.call('HGET', KEYS[1], 'nickname')
			local targetExpiry = redis.call('HGET', KEYS[1], 'expiresAt')
			if targetNicknameKey ~= nickname or not targetVersion or not targetDisplayName or not targetExpiry then
				return 'INCONSISTENT'
			end
			if targetOwner == owner then
				if oldKey == nickname and oldVersion == targetVersion then
					return 'RESERVED|' .. targetVersion .. '|' .. targetExpiry .. '|' .. targetDisplayName
				end
				return 'INCONSISTENT'
			end
			return 'CONFLICT'
		end
		if (oldKey or 'ABSENT') ~= expectedOldKey then return 'CONFLICT' end
		if oldKey and oldVersion then
			if redis.call('HGET', KEYS[3], 'ownerKey') ~= owner
				or redis.call('HGET', KEYS[3], 'version') ~= oldVersion then return 'INCONSISTENT' end
			redis.call('DEL', KEYS[3])
		end
		local expiresAt = now + 600000
		redis.call('HSET', KEYS[1], 'ownerKey', owner, 'nicknameKey', nickname, 'version', version,
			'nickname', displayName, 'expiresAt', expiresAt)
		redis.call('PEXPIREAT', KEYS[1], expiresAt)
		redis.call('HSET', KEYS[2], 'nicknameKey', nickname, 'version', version,
			'nickname', displayName, 'expiresAt', expiresAt)
		redis.call('PEXPIREAT', KEYS[2], expiresAt)
		return 'RESERVED|' .. version .. '|' .. expiresAt .. '|' .. displayName
		""", String.class);
	private static final DefaultRedisScript<Long> RELEASE = longScript("""
			local ownerExists = redis.call('EXISTS', KEYS[1])
			local nicknameExists = redis.call('EXISTS', KEYS[2])
			if ownerExists == 0 and nicknameExists == 0 then return 0 end
			local ownerNicknameKey = ownerExists == 1 and redis.call('HGET', KEYS[1], 'nicknameKey') or nil
			if nicknameExists == 0 then
				if ownerExists == 1 and ownerNicknameKey == ARGV[2] then return -1 end
				return 0
			end
			local nicknameOwner = redis.call('HGET', KEYS[2], 'ownerKey')
			local nicknameVersion = redis.call('HGET', KEYS[2], 'version')
			if not nicknameOwner or not nicknameVersion
				or redis.call('HGET', KEYS[2], 'nicknameKey') ~= ARGV[2]
				or nicknameOwner ~= ARGV[4] then return -1 end
			if nicknameOwner ~= ARGV[3] then
				if redis.call('EXISTS', KEYS[3]) == 0
					or redis.call('HGET', KEYS[3], 'nicknameKey') ~= ARGV[2]
					or redis.call('HGET', KEYS[3], 'version') ~= nicknameVersion then return -1 end
				return 0
			end
		if ownerExists == 0 or ownerNicknameKey ~= ARGV[2] then return -1 end
		local ownerVersion = redis.call('HGET', KEYS[1], 'version')
		if not ownerVersion or ownerVersion ~= nicknameVersion then return -1 end
			if ownerVersion ~= ARGV[1] then return 0 end
			redis.call('DEL', KEYS[1], KEYS[2])
			return 1
			""", Long.class);

	private final StringRedisTemplate redis;

	public RedisNicknameReservationAdapter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public NicknameReservationResult reserve(String ownerKey, String nicknameKey, String nickname) {
		return reserveOnce(ownerKey, nicknameKey, nickname);
	}

	private NicknameReservationResult reserveOnce(String ownerKey, String nicknameKey, String nickname) {
		String ownerRedisKey = ownerRedisKey(ownerKey);
		Map<Object, Object> existing = read(ownerRedisKey, "reservation_read");
		String oldNicknameKey = (String) existing.get("nicknameKey");
		if (!existing.isEmpty() && oldNicknameKey == null) throw unavailable("reservation_consistency", null);
		String targetRedisKey = nicknameRedisKey(nicknameKey);
		String version = UUID.randomUUID().toString();
		String oldRedisKey = oldNicknameKey == null ? targetRedisKey : nicknameRedisKey(oldNicknameKey);
		String result = execute(RESERVE, List.of(targetRedisKey, ownerRedisKey, oldRedisKey),
				"reservation_write", ownerKey, nicknameKey, version, nickname,
				oldNicknameKey == null ? "ABSENT" : oldNicknameKey);
		String[] parts = result.split("\\|", 4);
		return switch (parts[0]) {
			case "CONFLICT" -> throw new NicknameReservationConflictException();
			case "INCONSISTENT" -> throw unavailable("reservation_consistency", null);
			case "RESERVED" -> new NicknameReservationResult(parts[3], nicknameKey, parts[1],
					LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(parts[2])), KST));
			default -> throw unavailable("reservation_write", null);
		};
	}

	@Override
	public Optional<NicknameReservationResult> load(String ownerKey) {
		Map<Object, Object> owner = read(ownerRedisKey(ownerKey), "reservation_read");
		if (owner.isEmpty()) return Optional.empty();
		return Optional.of(toReservation(owner));
	}

	@Override
	public boolean isHeldBy(String nicknameKey, String ownerKey) {
		try {
			Map<Object, Object> target = read(nicknameRedisKey(nicknameKey), "reservation_read");
			if (target.isEmpty()) return false;
			validateNicknameRecord(target, nicknameKey);
			return ownerKey.equals(target.get("ownerKey"));
		} catch (NicknameReservationUnavailableException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw unavailable("reservation_read", exception);
		}
	}

	@Override
	public boolean isReservedByOther(String nicknameKey, String ownerKey) {
		Map<Object, Object> target = read(nicknameRedisKey(nicknameKey), "reservation_read");
		if (target.isEmpty()) return false;
		validateNicknameRecord(target, nicknameKey);
		return !ownerKey.equals(target.get("ownerKey"));
	}

	@Override
	public boolean releaseIfVersionMatches(String ownerKey, String nicknameKey, String version) {
		Map<Object, Object> target = read(nicknameRedisKey(nicknameKey), "reservation_cleanup");
		String actualOwner = (String) target.get("ownerKey");
		if (!target.isEmpty() && actualOwner == null) throw unavailable("reservation_consistency", null);
		String actualOwnerRedisKey = actualOwner == null ? ownerRedisKey(ownerKey) : ownerRedisKey(actualOwner);
		Long result = execute(RELEASE,
				List.of(ownerRedisKey(ownerKey), nicknameRedisKey(nicknameKey), actualOwnerRedisKey),
				"reservation_cleanup", version, nicknameKey, ownerKey, actualOwner == null ? ownerKey : actualOwner);
		if (result == -1L) throw unavailable("reservation_consistency", null);
		return result == 1L;
	}

	private NicknameReservationResult toReservation(Map<Object, Object> record) {
		String nickname = (String) record.get("nickname");
		String nicknameKey = (String) record.get("nicknameKey");
		String version = (String) record.get("version");
		Object expiresAtValue = record.get("expiresAt");
		if (nickname == null || nicknameKey == null || version == null || expiresAtValue == null) {
			throw unavailable("reservation_consistency", null);
		}
		try {
			return new NicknameReservationResult(nickname, nicknameKey, version,
					LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(expiresAtValue.toString())), KST));
		} catch (RuntimeException exception) {
			throw unavailable("reservation_consistency", exception);
		}
	}

	private void validateNicknameRecord(Map<Object, Object> record, String requestedNicknameKey) {
		if (record.get("ownerKey") == null || record.get("version") == null
				|| record.get("nickname") == null || record.get("expiresAt") == null
				|| !requestedNicknameKey.equals(record.get("nicknameKey"))) {
			throw unavailable("reservation_consistency", null);
		}
		try {
			Long.parseLong(record.get("expiresAt").toString());
		} catch (RuntimeException exception) {
			throw unavailable("reservation_consistency", exception);
		}
	}

	private Map<Object, Object> read(String key, String stage) {
		try {
			return redis.opsForHash().entries(key);
		} catch (RuntimeException exception) {
			throw unavailable(stage, exception);
		}
	}

	private <T> T execute(DefaultRedisScript<T> script, List<String> keys, String stage, String... arguments) {
		try {
			T result = redis.execute(script, keys, (Object[]) arguments);
			if (result == null) throw new IllegalStateException("Redis returned no result");
			return result;
		} catch (RuntimeException exception) {
			throw unavailable(stage, exception);
		}
	}

	private static String ownerRedisKey(String ownerKey) {
		return "nickname-reservations:v1:{nickname-holds-v1}:owner:" + ownerKey;
	}

	private static String nicknameRedisKey(String nicknameKey) {
		return "nickname-reservations:v1:{nickname-holds-v1}:nickname:" + nicknameKey;
	}

	private static NicknameReservationUnavailableException unavailable(String stage, Throwable cause) {
		return new NicknameReservationUnavailableException(stage, cause);
	}

	private static DefaultRedisScript<String> stringScript(String source, Class<String> resultType) {
		return new DefaultRedisScript<>(source, resultType);
	}

	private static DefaultRedisScript<Long> longScript(String source, Class<Long> resultType) {
		return new DefaultRedisScript<>(source, resultType);
	}
}
