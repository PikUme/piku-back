package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.exception.SignupFailure;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.port.out.SignupStorePort;
import com.pikume.back.user.auth.domain.SignupAuthentication;
import com.pikume.back.user.auth.domain.UserAgreement;
import com.pikume.back.user.auth.domain.UserOAuthAccount;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.vo.Email;
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
import java.util.Locale;
import java.util.Optional;

@Repository
public class SignupPersistenceAdapter implements SignupStorePort {
    @PersistenceContext private EntityManager em;

    @Override
    public Optional<SignupAuthentication> lockProof(String hash) {
        return em.createQuery("select p from SignupAuthentication p where p.tokenHash=:hash", SignupAuthentication.class)
        .setParameter("hash", hash).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }

    @Override
    public void saveProof(SignupAuthentication proof) {
        em.persist(proof);
    }

    @Override
    public Optional<Verification> lockChallenge(String id) {
        return em.createQuery("select v from Verification v where v.challengeId=:id", Verification.class)
        .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }

    @Override
    public void saveChallenge(Verification challenge) {
        if (challenge.getId()==null)em.persist(challenge);
    }

    @Override
    public Optional<User> findUserByEmail(String email) {
        // The existing DB collation can be broader; auto-link additionally checks exact case-insensitive equality in Application.
        return em.createQuery("select u from User u where lower(cast(u.email as string))=:email", User.class)
        .setParameter("email", new Email(email).value().toLowerCase(Locale.ROOT)).getResultStream().findFirst();
    }

    @Override
    public Optional<User> findUser(String id) {
        return Optional.ofNullable(em.find(User.class, id));
    }

    @Override
    public User createUser(User user) {
        em.persist(user);
        em.flush();
        return user;
    }

    @Override
    public Optional<UserOAuthAccount> findAccount(String provider, String subject) {
        return em.createQuery("select a from UserOAuthAccount a where a.provider=:provider and a.providerSubject=:subject", UserOAuthAccount.class)
        .setParameter("provider", provider).setParameter("subject", subject).getResultStream()
        .filter(a -> a.getProviderSubject().equals(subject)).findFirst();
    }

    @Override
    public Optional<UserOAuthAccount> findUserAccount(String userId, String provider) {
        return em.createQuery("select a from UserOAuthAccount a where a.userId=:user and a.provider=:provider", UserOAuthAccount.class)
        .setParameter("user", userId).setParameter("provider", provider).getResultStream().findFirst();
    }

    @Override
    public void createAccount(UserOAuthAccount account) {
        em.persist(account);
        em.flush();
    }

    @Override
    public void recordAgreement(UserAgreement agreement) {
        em.persist(agreement);
    }

    @Override
    public Instant reserveEmailSend(String emailHash, String originHash, int emailLimit, int originLimit, int resendSeconds) {
        // Migration supplies a permanent mutex: first use of an absent bucket is serialized across instances.
        if (em.find(SignupRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE)==null)
        throw new IllegalStateException("Signup rate limit guard is missing");
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        SignupRateLimit email=bucket("email:"+emailHash, now), origin=bucket("ip:"+originHash, now);
        if (email.getSendCount()>=emailLimit || origin.getSendCount()>=originLimit)
        throw new SignupFlowException(SignupFailure.RATE_LIMITED);
        if (email.getLastSentAt() != null && now.isBefore(email.getLastSentAt().plusSeconds(resendSeconds))) {
            throw new SignupFlowException(SignupFailure.RATE_LIMITED);
        }
        email.increment(now);
        origin.increment(now);
        return now;
    }
    private SignupRateLimit bucket(String key, Instant now) {
        SignupRateLimit bucket=em.find(SignupRateLimit.class, key);
        if (bucket==null) {
            bucket=new SignupRateLimit(key, now);
            em.persist(bucket);
        }
        bucket.resetIfExpired(now);
        return bucket;
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
    public void purgeExpired(Instant now) {
        // Cleanup must not hold range gap locks that block a concurrent proof insert while waiting for its challenge.
        em.createQuery("delete from SignupAuthentication p where p.expiresAt<=:now").setParameter("now", now).executeUpdate();
        em.createQuery("delete from Verification v where v.challengeId is not null and v.expiresAt<=:now")
        .setParameter("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).executeUpdate();
        // Use the same guard as send reservation so cleanup cannot delete a concurrently refreshed bucket.
        if (em.find(SignupRateLimit.class, "guard", LockModeType.PESSIMISTIC_WRITE)!=null)
        em.createQuery("delete from SignupRateLimit b where b.bucketKey<>'guard' and b.windowStartedAt<=:cutoff and (b.lastSentAt is null or b.lastSentAt<=:cutoff)")
        .setParameter("cutoff", now.minusSeconds(3600)).executeUpdate();
    }
}
