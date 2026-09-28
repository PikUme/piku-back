package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.domain.Verification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class EmailVerificationPersistenceAdapter implements EmailVerificationStorePort {
    @PersistenceContext private EntityManager em;

    @Override
    public Optional<Verification> lockVerification(String id) {
        return em.createQuery("select v from Verification v where v.emailVerificationId=:id", Verification.class)
        .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }

    @Override
    public Optional<Verification> lockLatestVerification(String email) {
        return em.createQuery("select v from Verification v where lower(v.email)=:email and v.type=:type and v.emailVerificationId is not null order by v.id desc", Verification.class)
            .setParameter("email", email.toLowerCase(java.util.Locale.ROOT))
            .setParameter("type", com.pikume.back.user.auth.domain.vo.VerificationType.SIGN_UP)
            .setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }

    @Override
    public Optional<Verification> lockByTokenHash(String tokenHash) {
        return em.createQuery("select v from Verification v where v.verificationTokenHash=:hash", Verification.class)
            .setParameter("hash", tokenHash).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }

    @Override
    public void saveVerification(Verification verification) {
        if (verification.getId()==null)em.persist(verification);
    }

    @Override
    public Instant reserveEmailSend(String emailHash, String originHash, int emailLimit, int originLimit, int resendSeconds) {
        // Migration supplies a permanent mutex: first use of an absent bucket is serialized across instances.
        if (em.find(EmailVerificationRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE)==null)
        throw new IllegalStateException("Email verification rate limit guard is missing");
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        EmailVerificationRateLimit email=bucket("email:"+emailHash, now), origin=bucket("ip:"+originHash, now);
        if (email.getSendCount()>=emailLimit || origin.getSendCount()>=originLimit)
        throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED);
        if (email.getLastSentAt() != null && now.isBefore(email.getLastSentAt().plusSeconds(resendSeconds))) {
            throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED);
        }
        email.increment(now);
        origin.increment(now);
        return now;
    }
    private EmailVerificationRateLimit bucket(String key, Instant now) {
        EmailVerificationRateLimit bucket=em.find(EmailVerificationRateLimit.class, key);
        if (bucket==null) {
            bucket=new EmailVerificationRateLimit(key, now);
            em.persist(bucket);
        }
        bucket.resetIfExpired(now);
        return bucket;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public void purgeExpired(Instant now) {
        // Match send ordering: rate-limit guard before verification rows.
        em.find(EmailVerificationRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE);
        // Cleanup must not hold range gap locks that block concurrent email verification writes.
        em.createQuery("delete from Verification v where v.emailVerificationId is not null and v.expiresAt<=:now")
        .setParameter("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).executeUpdate();
        // Use the same guard as send reservation so cleanup cannot delete a concurrently refreshed bucket.
        if (em.find(EmailVerificationRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE)!=null)
        em.createQuery("delete from EmailVerificationRateLimit b where b.bucketKey<>'guard' and b.windowStartedAt<=:cutoff and (b.lastSentAt is null or b.lastSentAt<=:cutoff)")
        .setParameter("cutoff", now.minusSeconds(3600)).executeUpdate();
    }
}
