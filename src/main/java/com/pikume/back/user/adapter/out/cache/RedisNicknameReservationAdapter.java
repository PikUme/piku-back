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
			local oldVersion = redis.call('HGET', KEYS[2], 'version')
			local oldKey = redis.call('HGET', KEYS[2], 'nicknameKey')
			local oldDisplayName = redis.call('HGET', KEYS[1], 'nickname')
			local oldExpiry = tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0')
		local targetOwner = redis.call('HGET', KEYS[1], 'ownerKey')
		if targetOwner then
			if redis.call('HGET', KEYS[4], 'nicknameKey') ~= nickname
				or redis.call('HGET', KEYS[4], 'version') ~= redis.call('HGET', KEYS[1], 'version') then
				return 'INCONSISTENT'
			end
		end
			if oldKey == nickname and oldVersion then
				if redis.call('HGET', KEYS[1], 'ownerKey') ~= owner
					or redis.call('HGET', KEYS[1], 'version') ~= oldVersion then return 'INCONSISTENT' end
				return 'RESERVED|' .. oldVersion .. '|' .. oldExpiry .. '|' .. oldDisplayName
			end
		if targetOwner and targetOwner ~= owner then return 'CONFLICT' end
		if targetOwner == owner and redis.call('HGET', KEYS[1], 'version') ~= oldVersion then return 'INCONSISTENT' end
		if oldKey and oldVersion then
			if redis.call('HGET', KEYS[3], 'ownerKey') ~= owner
				or redis.call('HGET', KEYS[3], 'version') ~= oldVersion then return 'INCONSISTENT' end
			redis.call('DEL', KEYS[3])
		end
		local expiresAt = now + 600000
		redis.call('HSET', KEYS[1], 'ownerKey', owner, 'nicknameKey', nickname, 'version', version,
			'nickname', displayName, 'expiresAt', expiresAt)
		redis.call('PEXPIREAT', KEYS[1], expiresAt)
		redis.call('HSET', KEYS[2], 'nicknameKey', nickname, 'version', version)
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
	private static final DefaultRedisScript<List> PAIR_READ = new DefaultRedisScript<>("""
			local ownerExists = redis.call('EXISTS', KEYS[1])
			local nicknameExists = redis.call('EXISTS', KEYS[2])
			if ownerExists == 0 and nicknameExists == 0 then return {'MISSING'} end
			if ownerExists == 0 or nicknameExists == 0 then return {'INCONSISTENT'} end
			local ownerVersion = redis.call('HGET', KEYS[1], 'version')
			local ownerNicknameKey = redis.call('HGET', KEYS[1], 'nicknameKey')
			local nicknameOwner = redis.call('HGET', KEYS[2], 'ownerKey')
			local nicknameVersion = redis.call('HGET', KEYS[2], 'version')
			local indexedNicknameKey = redis.call('HGET', KEYS[2], 'nicknameKey')
			local displayName = redis.call('HGET', KEYS[2], 'nickname')
			local expiresAt = redis.call('HGET', KEYS[2], 'expiresAt')
			if not ownerVersion or not ownerNicknameKey or not nicknameOwner or not nicknameVersion
				or not indexedNicknameKey or not displayName or not expiresAt
				or ownerNicknameKey ~= ARGV[2] or indexedNicknameKey ~= ARGV[2]
				or nicknameOwner ~= ARGV[1] or ownerVersion ~= nicknameVersion then
				return {'INCONSISTENT'}
			end
			return {'PAIR', displayName, indexedNicknameKey, ownerVersion, expiresAt}
			""", List.class);

	private final StringRedisTemplate redis;

	public RedisNicknameReservationAdapter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public NicknameReservationResult reserve(String ownerKey, String nicknameKey, String nickname) {
		String ownerRedisKey = ownerRedisKey(ownerKey);
		Map<Object, Object> existing = read(ownerRedisKey, "reservation_read");
		String oldNicknameKey = (String) existing.get("nicknameKey");
		if (!existing.isEmpty() && oldNicknameKey == null) throw unavailable("reservation_consistency", null);
		String targetRedisKey = nicknameRedisKey(nicknameKey);
		Map<Object, Object> target = read(targetRedisKey, "reservation_read");
		String targetOwner = (String) target.get("ownerKey");
		if (!target.isEmpty() && targetOwner == null) throw unavailable("reservation_consistency", null);
		String targetOwnerRedisKey = targetOwner == null ? ownerRedisKey : ownerRedisKey(targetOwner);
		String version = UUID.randomUUID().toString();
		String oldRedisKey = oldNicknameKey == null ? targetRedisKey : nicknameRedisKey(oldNicknameKey);
		String result = execute(RESERVE, List.of(targetRedisKey, ownerRedisKey, oldRedisKey, targetOwnerRedisKey),
				"reservation_write", ownerKey, nicknameKey, version, nickname);
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
		String nicknameKey = (String) owner.get("nicknameKey");
		if (owner.isEmpty()) return Optional.empty();
		if (nicknameKey == null) throw unavailable("reservation_consistency", null);
		List<?> pair = readPair(ownerKey, nicknameKey);
		if ("MISSING".equals(pair.get(0))) return Optional.empty();
		if (!"PAIR".equals(pair.get(0))) throw unavailable("reservation_consistency", null);
		return Optional.of(new NicknameReservationResult((String) pair.get(1), (String) pair.get(2),
				(String) pair.get(3), LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong((String) pair.get(4))), KST)));
	}

	@Override
	public boolean isHeldBy(String nicknameKey, String ownerKey) {
		try {
			Map<Object, Object> target = read(nicknameRedisKey(nicknameKey), "reservation_read");
			if (target.isEmpty()) {
				Map<Object, Object> owner = read(ownerRedisKey(ownerKey), "reservation_read");
				if (owner.isEmpty() || !nicknameKey.equals(owner.get("nicknameKey"))) return false;
				List<?> pair = readPair(ownerKey, nicknameKey);
				if ("MISSING".equals(pair.get(0))) return false;
				if (!"PAIR".equals(pair.get(0))) throw unavailable("reservation_consistency", null);
				return true;
			}
			String actualOwner = (String) target.get("ownerKey");
			if (actualOwner == null) throw unavailable("reservation_consistency", null);
			List<?> pair = readPair(actualOwner, nicknameKey);
			if ("MISSING".equals(pair.get(0))) return false;
			if (!"PAIR".equals(pair.get(0))) throw unavailable("reservation_consistency", null);
			return ownerKey.equals(actualOwner);
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
		String actualOwner = (String) target.get("ownerKey");
		if (actualOwner == null) throw unavailable("reservation_consistency", null);
		List<?> pair = readPair(actualOwner, nicknameKey);
		if ("MISSING".equals(pair.get(0))) return false;
		if (!"PAIR".equals(pair.get(0))) throw unavailable("reservation_consistency", null);
		return !ownerKey.equals(actualOwner);
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

	private List<?> readPair(String ownerKey, String nicknameKey) {
		try {
			List<?> result = redis.execute(PAIR_READ,
					List.of(ownerRedisKey(ownerKey), nicknameRedisKey(nicknameKey)), ownerKey, nicknameKey);
			if (result == null || result.isEmpty()) throw new IllegalStateException("Redis returned no pair result");
			return result;
		} catch (RuntimeException exception) {
			throw unavailable("reservation_read", exception);
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
