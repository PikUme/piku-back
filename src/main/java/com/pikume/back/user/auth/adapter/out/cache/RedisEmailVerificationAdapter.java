package com.pikume.back.user.auth.adapter.out.cache;

import com.pikume.back.user.auth.application.dto.EmailVerificationReservation;
import com.pikume.back.user.auth.application.dto.EmailVerificationResult;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisEmailVerificationAdapter implements EmailVerificationStorePort {

	private static final DefaultRedisScript<String> PREPARE = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - 3600000)
			local count = redis.call('ZCARD', KEYS[3])
			local last = redis.call('ZREVRANGE', KEYS[3], 0, 0, 'WITHSCORES')
			local resendAt = 0
			if #last > 0 then resendAt = tonumber(last[2]) + tonumber(ARGV[4]) * 1000 end
			local hourlyAt = 0
			if count >= tonumber(ARGV[3]) then
				local first = redis.call('ZRANGE', KEYS[3], 0, 0, 'WITHSCORES')
				hourlyAt = tonumber(first[2]) + 3600000
			end
			local nextAt = math.max(resendAt, hourlyAt)
			if nextAt > now then return 'LIMIT|' .. nextAt end
			redis.call('DEL', KEYS[1], KEYS[2])
			local deadline = now + 300000
			redis.call('HSET', KEYS[1], 'generation', ARGV[1], 'codeHash', ARGV[2],
				'active', '0', 'attempts', '0', 'deadline', deadline)
			redis.call('ZADD', KEYS[3], now, ARGV[1])
			redis.call('EXPIRE', KEYS[1], 300)
			redis.call('EXPIRE', KEYS[3], 3601)
			local hourlyNext = 0
			if count + 1 >= tonumber(ARGV[3]) then
				local first = redis.call('ZRANGE', KEYS[3], 0, 0, 'WITHSCORES')
				hourlyNext = tonumber(first[2]) + 3600000
			end
			return 'OK|' .. deadline .. '|' .. math.max(now + tonumber(ARGV[4]) * 1000, hourlyNext)
			""");
	private static final DefaultRedisScript<String> ACTIVATE = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1]
				or redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[2]
				or redis.call('HGET', KEYS[1], 'active') ~= '0'
				or tonumber(redis.call('HGET', KEYS[1], 'deadline') or '0') <= now then
				return '0'
			end
			redis.call('HSET', KEYS[1], 'active', '1')
			return '1'
			""");
	private static final DefaultRedisScript<String> VERIFY = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			local deadline = tonumber(redis.call('HGET', KEYS[1], 'deadline') or '0')
			if redis.call('HGET', KEYS[1], 'active') ~= '1' or deadline <= now then return 'INVALID' end
			local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
			if attempts >= tonumber(ARGV[3]) then return 'ATTEMPTS' end
			if redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[1] then
				attempts = attempts + 1
				redis.call('HSET', KEYS[1], 'attempts', attempts)
				if attempts >= tonumber(ARGV[3]) then return 'ATTEMPTS' end
				return 'MISMATCH'
			end
			redis.call('DEL', KEYS[1])
			redis.call('HSET', KEYS[2], 'tokenHash', ARGV[2], 'expiresAt', now + 600000)
			redis.call('EXPIRE', KEYS[2], 600)
			return 'OK|' .. (now + 600000)
			""");
	private static final DefaultRedisScript<String> VALIDATE_TOKEN = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			if redis.call('HGET', KEYS[1], 'tokenHash') ~= ARGV[1]
				or tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0') <= now then return '0' end
			return '1'
			""");
	private static final DefaultRedisScript<String> REMOVE_TOKEN = script("""
			if redis.call('HGET', KEYS[1], 'tokenHash') ~= ARGV[1] then return '0' end
			return tostring(redis.call('DEL', KEYS[1]))
			""");

	private final StringRedisTemplate redis;

	public RedisEmailVerificationAdapter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public EmailVerificationReservation prepare(String emailKey, String generation, String codeHash,
			int hourlyLimit, int resendSeconds) {
		String response = execute(PREPARE, emailKey, List.of(":auth", ":proof", ":sends"),
				"send_prepare", generation, codeHash, hourlyLimit, resendSeconds);
		String[] parts = response.split("\\|");
		if (parts[0].equals("LIMIT")) {
			throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED,
					localDateTime(Long.parseLong(parts[1])));
		}
		return new EmailVerificationReservation(generation, localDateTime(Long.parseLong(parts[1])),
				localDateTime(Long.parseLong(parts[2])));
	}

	@Override
	public boolean activate(String emailKey, String generation, String codeHash) {
		return "1".equals(execute(ACTIVATE, emailKey, List.of(":auth"), "send_activate", generation, codeHash));
	}

	@Override
	public EmailVerificationAttempt verify(String emailKey, String submittedCodeHash, String tokenHash, String rawToken, int maxAttempts) {
		String response = execute(VERIFY, emailKey, List.of(":auth", ":proof"),
				"code_verify", submittedCodeHash, tokenHash, maxAttempts);
		if (response.equals("INVALID")) return failure(EmailVerificationFailure.VERIFICATION_INVALID);
		if (response.equals("MISMATCH")) return failure(EmailVerificationFailure.CODE_MISMATCH);
		if (response.equals("ATTEMPTS")) return failure(EmailVerificationFailure.ATTEMPTS_EXHAUSTED);
		return new EmailVerificationAttempt(null,
				new EmailVerificationResult(rawToken, localDateTime(Long.parseLong(response.substring(3)))));
	}

	@Override
	public boolean isTokenValid(String emailKey, String tokenHash) {
		return "1".equals(execute(VALIDATE_TOKEN, emailKey, List.of(":proof"), "signup_token_check", tokenHash));
	}

	@Override
	public boolean removeToken(String emailKey, String tokenHash) {
		return "1".equals(execute(REMOVE_TOKEN, emailKey, List.of(":proof"), "signup_token_cleanup", tokenHash));
	}

	private String execute(DefaultRedisScript<String> script, String emailKey, List<String> suffixes,
			String stage, Object... arguments) {
		String base = "signup-email:{" + emailKey + "}";
		List<String> keys = suffixes.stream().map(base::concat).toList();
		try {
			Object[] stringArguments = Arrays.stream(arguments).map(String::valueOf).toArray();
			String result = redis.execute(script, keys, stringArguments);
			if (result == null) throw new IllegalStateException("Redis returned no result");
			return result;
		} catch (EmailVerificationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new EmailVerificationException(
					EmailVerificationFailure.VERIFICATION_UNAVAILABLE, exception, stage);
		}
	}

	private static EmailVerificationAttempt failure(EmailVerificationFailure failure) {
		return new EmailVerificationAttempt(failure, null);
	}

	private static LocalDateTime localDateTime(long millis) {
		return LocalDateTime.of(1970, 1, 1, 9, 0).plus(millis, ChronoUnit.MILLIS);
	}

	private static DefaultRedisScript<String> script(String source) {
		return new DefaultRedisScript<>(source, String.class);
	}
}
