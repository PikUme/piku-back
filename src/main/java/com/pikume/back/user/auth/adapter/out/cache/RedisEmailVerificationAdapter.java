package com.pikume.back.user.auth.adapter.out.cache;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisEmailVerificationAdapter implements EmailVerificationStorePort {

	private static final DefaultRedisScript<Long> RESERVE = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			redis.call('HSET', KEYS[1], 'generation', ARGV[1], 'codeHash', ARGV[2],
				'active', '0', 'deadline', now + 300000)
			redis.call('EXPIRE', KEYS[1], 300)
			redis.call('DEL', KEYS[2])
			return 1
			""", Long.class);
	private static final DefaultRedisScript<String> ACTIVATE = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1]
				or redis.call('HGET', KEYS[1], 'active') ~= '0'
				or tonumber(redis.call('HGET', KEYS[1], 'deadline') or '0') <= now then
				return ''
			end
			redis.call('HSET', KEYS[1], 'active', '1')
			return redis.call('HGET', KEYS[1], 'deadline')
			""", String.class);
	private static final DefaultRedisScript<String> VERIFY = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			local deadline = tonumber(redis.call('HGET', KEYS[1], 'deadline') or '0')
			if deadline == 0 then return 'NOT_FOUND' end
			if deadline <= now then
				redis.call('DEL', KEYS[1])
				return 'EXPIRED'
			end
			if redis.call('HGET', KEYS[1], 'active') ~= '1' then return 'INACTIVE' end
			if redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[1] then return 'MISMATCH' end
			redis.call('DEL', KEYS[1])
			redis.call('HSET', KEYS[2], 'version', ARGV[2], 'tokenHash', ARGV[3], 'expiresAt', now + 600000)
			redis.call('EXPIRE', KEYS[2], 600)
			return 'VERIFIED|' .. ARGV[2] .. '|' .. ARGV[3] .. '|' .. (now + 600000)
			""", String.class);
	private static final DefaultRedisScript<String> LOAD_PROOF = script("""
			local time = redis.call('TIME')
			local now = time[1] * 1000 + math.floor(time[2] / 1000)
			local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0')
			if expiresAt <= now then
				redis.call('DEL', KEYS[1])
				return ''
			end
			if redis.call('HGET', KEYS[1], 'tokenHash') ~= ARGV[1] then return '' end
			return redis.call('HGET', KEYS[1], 'version') .. '|' .. ARGV[1] .. '|' .. expiresAt
			""", String.class);
	private static final DefaultRedisScript<Long> REMOVE_PROOF = script("""
			if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end
			return redis.call('DEL', KEYS[1])
			""", Long.class);

	private final StringRedisTemplate redis;

	public RedisEmailVerificationAdapter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean reserve(String emailKey, String generation, String codeHash) {
		return execute(RESERVE, emailKey, List.of(":auth", ":proof"), "code_reserve", generation, codeHash) == 1L;
	}

	@Override
	public Optional<LocalDateTime> activate(String emailKey, String generation) {
		String deadline = execute(ACTIVATE, emailKey, List.of(":auth"), "code_activate", generation);
		return deadline.isEmpty() ? Optional.empty() : Optional.of(localDateTime(Long.parseLong(deadline)));
	}

	@Override
	public VerificationResult verify(String emailKey, String submittedCodeHash, String version, String tokenHash) {
		String result = execute(VERIFY, emailKey, List.of(":auth", ":proof"), "code_verify",
				submittedCodeHash, version, tokenHash);
		String[] values = result.split("\\|", 4);
		if (values[0].equals("VERIFIED")) {
			SignupEmailProof proof = new SignupEmailProof(values[1], values[2], localDateTime(Long.parseLong(values[3])));
			return new VerificationResult(VerificationStatus.VERIFIED, proof);
		}
		return new VerificationResult(VerificationStatus.valueOf(values[0]), null);
	}

	@Override
	public Optional<SignupEmailProof> loadProof(String emailKey, String tokenHash) {
		String result = execute(LOAD_PROOF, emailKey, List.of(":proof"), "proof_load", tokenHash);
		if (result.isEmpty()) {
			return Optional.empty();
		}
		String[] values = result.split("\\|", 3);
		return Optional.of(new SignupEmailProof(values[0], values[1], localDateTime(Long.parseLong(values[2]))));
	}

	@Override
	public boolean removeProofIfVersionMatches(String emailKey, String version) {
		return execute(REMOVE_PROOF, emailKey, List.of(":proof"), "proof_cleanup", version) == 1L;
	}

	private <T> T execute(DefaultRedisScript<T> script, String emailKey, List<String> suffixes,
			String stage, Object... arguments) {
		String base = "signup-email:{" + emailKey + "}";
		List<String> keys = suffixes.stream().map(base::concat).toList();
		try {
			Object[] stringArguments = Arrays.stream(arguments).map(String::valueOf).toArray();
			T result = redis.execute(script, keys, stringArguments);
			if (result == null) {
				throw new IllegalStateException("Redis returned no result");
			}
			return result;
		} catch (RuntimeException exception) {
			throw new EmailVerificationException(
					EmailVerificationFailure.VERIFICATION_UNAVAILABLE, exception, stage);
		}
	}

	private static LocalDateTime localDateTime(long millis) {
		return LocalDateTime.of(1970, 1, 1, 9, 0).plus(millis, ChronoUnit.MILLIS);
	}

	private static <T> DefaultRedisScript<T> script(String source, Class<T> resultType) {
		return new DefaultRedisScript<>(source, resultType);
	}
}
