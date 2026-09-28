package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

@Repository
public class EmailVerificationPersistenceAdapter implements EmailVerificationStorePort {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@PersistenceContext
	private EntityManager em;

	@Override
	public Optional<Verification> lockVerification(String id) {
		return em.createQuery("select v from Verification v where v.emailVerificationId=:id", Verification.class)
				.setParameter("id", id)
				.setLockMode(LockModeType.PESSIMISTIC_WRITE)
				.getResultStream()
				.findFirst();
	}

	@Override
	public Optional<Verification> lockLatestVerification(String email) {
		return em.createQuery("""
				select v from Verification v
				where lower(v.email)=:email and v.type=:type and v.emailVerificationId is not null
				order by v.id desc
				""", Verification.class)
				.setParameter("email", email.toLowerCase(Locale.ROOT))
				.setParameter("type", VerificationType.SIGN_UP)
				.setMaxResults(1)
				.setLockMode(LockModeType.PESSIMISTIC_WRITE)
				.getResultStream()
				.findFirst();
	}

	@Override
	public Optional<Verification> lockByTokenHash(String tokenHash) {
		return em.createQuery("select v from Verification v where v.verificationTokenHash=:hash", Verification.class)
				.setParameter("hash", tokenHash)
				.setLockMode(LockModeType.PESSIMISTIC_WRITE)
				.getResultStream()
				.findFirst();
	}

	@Override
	public void saveVerification(Verification verification) {
		if (verification.getId() == null) {
			em.persist(verification);
		}
	}

	@Override
	public LocalDateTime reserveEmailSend(String emailHash, String originHash, int emailLimit, int originLimit, int resendSeconds) {
		// 여러 서버에 첫 요청이 동시에 들어와도 발송 횟수를 차례로 확인하도록 공통 행을 잠근다.
		if (em.find(EmailVerificationRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE) == null) {
			throw new IllegalStateException("Email verification rate limit guard is missing");
		}
		// 저장 후 읽어 온 발송 시각과 일치하도록 DB와 같은 마이크로초 정밀도를 사용한다.
		LocalDateTime now = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MICROS);
		EmailVerificationRateLimit email = bucket("email:" + emailHash, now);
		EmailVerificationRateLimit origin = bucket("ip:" + originHash, now);
		if (email.getSendCount() >= emailLimit || origin.getSendCount() >= originLimit) {
			throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED);
		}
		if (email.getLastSentAt() != null && now.isBefore(email.getLastSentAt().plusSeconds(resendSeconds))) {
			throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED);
		}
		email.increment(now);
		origin.increment(now);
		return now;
	}

	private EmailVerificationRateLimit bucket(String key, LocalDateTime now) {
		EmailVerificationRateLimit bucket = em.find(EmailVerificationRateLimit.class, key);
		if (bucket == null) {
			bucket = new EmailVerificationRateLimit(key, now);
			em.persist(bucket);
		}
		bucket.resetIfExpired(now);
		return bucket;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public void purgeExpired(LocalDateTime now) {
		// 발송과 같은 순서로 제한 잠금 행을 먼저 잠근 뒤 인증 행을 정리한다.
		em.find(EmailVerificationRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE);
		// 새 인증 요청의 삽입을 막는 범위 잠금을 줄이기 위해 READ_COMMITTED에서 정리한다.
		em.createQuery("delete from Verification v where v.emailVerificationId is not null and v.expiresAt<=:now")
				.setParameter("now", now)
				.executeUpdate();
		// 발송 중 갱신되는 제한 기록을 삭제하지 않도록 발송과 같은 잠금을 유지한다.
		if (em.find(EmailVerificationRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE) != null) {
			em.createQuery("""
					delete from EmailVerificationRateLimit b
					where b.bucketKey<>'guard' and b.windowStartedAt<=:cutoff
					and (b.lastSentAt is null or b.lastSentAt<=:cutoff)
					""")
					.setParameter("cutoff", now.minusSeconds(3600))
					.executeUpdate();
		}
	}
}
