package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.service.SignupFlowService;
import com.pikume.back.user.auth.domain.Verification;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Uses the shipped Flyway schema, real services and observed InnoDB lock waits. */
@Testcontainers
@Tag("mysql-migration")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SignupConcurrencyMySqlIntegrationTest extends SignupPersistenceTestSupport {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @MockitoSpyBean SignupPersistenceAdapter observed;
    ExecutorService workers;
    JdbcTemplate observer;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username",MYSQL::getUsername);
        properties.add("spring.datasource.password",MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.MySQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
        properties.add("spring.flyway.enabled",()->"true");
    }

    @BeforeEach void workers() {
        workers=Executors.newFixedThreadPool(2);
        observer=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
    }
    @AfterEach void stopWorkers() throws Exception {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"false,true,true","true,true,true","true,false,true","true,true,false"})
    void consentUniquenessRacesUseCommittedRecovery(boolean social,boolean sameEmail,boolean sameSubject) throws Exception {
        String first=social?socialProof("Subject","same@gmail.com"):emailProof("same@gmail.com");
        String second=social?socialProof(sameSubject?"Subject":"OtherSubject",sameEmail?"same@gmail.com":"other@gmail.com")
            :emailProof("same@gmail.com");
        CyclicBarrier bothReadAbsent=new CyclicBarrier(2);
        AtomicInteger reads=new AtomicInteger();
        doAnswer(call->{
            Object result=call.callRealMethod();
            if(reads.incrementAndGet()<=2)bothReadAbsent.await(10,TimeUnit.SECONDS);
            return result;
        }).when(observed).findUserByEmail(anyString());

        Future<Object> a=workers.submit(()->outcome(()->service.agree(new SignupAgreementCommand(first,"caller",agreements))));
        Future<Object> b=workers.submit(()->outcome(()->service.agree(new SignupAgreementCommand(second,"caller",agreements))));
        List<Object> results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        if(social&&sameSubject) {
            assertThat(results).allSatisfy(value->assertThat(value).isInstanceOf(SignupProofResult.class));
            assertThat(((SignupProofResult)results.get(0)).progress().userId()).isEqualTo(((SignupProofResult)results.get(1)).progress().userId());
        } else {
            assertThat(results.stream().filter(SignupProofResult.class::isInstance)).hasSize(1);
            assertThat(results).contains(SignupFailure.EMAIL_ALREADY_REGISTERED);
        }
        assertThat(count("User")).isEqualTo(1);
        assertThat(count("UserAgreement")).isEqualTo(1);
        assertThat(count("UserOAuthAccount")).isEqualTo(social?1:0);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void verificationFirstPreventsResendFromReopeningTheConsumedChallenge(boolean social) throws Exception {
        Challenge challenge=readyChallenge(social);
        Gate verificationLocked=pauseFirstChallengeLock();
        Future<Object> verification=workers.submit(()->outcome(()->verify(challenge,"123456")));
        try {
            verificationLocked.awaitEntered();
            Future<Object> resend=workers.submit(()->outcome(()->resend(challenge)));
            awaitDatabaseWait();
            verificationLocked.release();
            assertThat(verification.get(15,TimeUnit.SECONDS)).isInstanceOf(SignupProofResult.class);
            // Social email verification consumes the challenge, while its signup proof remains unconsumed.
            assertThat(resend.get(15,TimeUnit.SECONDS)).isEqualTo(SignupFailure.CHALLENGE_INVALID);
            assertThat(count("SignupAuthentication")).isEqualTo(1);
            assertThat((Instant)tx.required(()->store.lockChallenge(challenge.id()).orElseThrow().getConsumedAt())).isNotNull();
        } finally {verificationLocked.release();}
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void resendFirstInvalidatesOldCodeWhileDeliveryIsPending(boolean social) throws Exception {
        Challenge challenge=readyChallenge(social);
        Gate resendLocked=pauseFirstChallengeLock(),mail=new Gate();
        doAnswer(call->{mail.block();return "654321";}).when(sender).issueVerificationEmail(anyString());
        Future<Object> resend=workers.submit(()->outcome(()->resend(challenge)));
        try {
            resendLocked.awaitEntered();
            Future<Object> oldVerification=workers.submit(()->outcome(()->verify(challenge,"123456")));
            awaitDatabaseWait();
            resendLocked.release();mail.awaitEntered();
            assertThat(oldVerification.get(15,TimeUnit.SECONDS)).isEqualTo(SignupFailure.CHALLENGE_INVALID);
            assertThat((Instant)tx.required(()->store.lockChallenge(challenge.id()).orElseThrow().getConsumedAt())).isNull();
            mail.release();assertThat(resend.get(15,TimeUnit.SECONDS)).isInstanceOf(EmailSignupChallengeResult.class);
            assertThat(outcome(()->verify(challenge,"123456"))).isEqualTo(SignupFailure.CODE_MISMATCH);
            assertThat(verify(challenge,"654321").progress().nextAction()).isEqualTo(SignupNextAction.AGREEMENTS);
            assertThat(count("SignupAuthentication")).isEqualTo(1);
        } finally {resendLocked.release();mail.release();}
    }

    private Gate pauseFirstChallengeLock() {
        Gate gate=new Gate();AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{Object result=call.callRealMethod();if(calls.incrementAndGet()==1)gate.block();return result;})
            .when(observed).lockChallenge(anyString());
        return gate;
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void cleanupFirstRejectsExpiredArtifactsWithoutCreatingAMember(Operation operation) throws Exception {
        Attempt attempt=attempt(operation);
        expire(attempt,Instant.now().minusSeconds(1));
        Gate deleted=new Gate();
        doAnswer(call->{call.callRealMethod();deleted.block();return null;}).when(observed).purgeExpired(any());
        Future<?> cleanup=workers.submit(service::purgeExpiredSignupArtifacts);
        try {
            deleted.awaitEntered();
            Future<Object> request=workers.submit(()->outcome(()->execute(attempt)));
            awaitDatabaseWait();deleted.release();cleanup.get(15,TimeUnit.SECONDS);
            assertThat(request.get(15,TimeUnit.SECONDS)).isEqualTo(operation==Operation.CONSENT?SignupFailure.PROOF_INVALID:SignupFailure.CHALLENGE_INVALID);
            assertThat(count("User")).isZero();assertThat(count("UserAgreement")).isZero();
            assertThat(count("Verification")).isZero();
            assertThat(count("SignupAuthentication")).isEqualTo(operation==Operation.SOCIAL_EMAIL?1:0);
        } finally {deleted.release();}
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void validatedWriteFirstSurvivesConcurrentExpiryCleanup(boolean consent) throws Exception {
        Attempt attempt=attempt(consent?Operation.CONSENT:Operation.EMAIL);
        Instant expires=Instant.now().plusSeconds(2);expire(attempt,expires);
        Gate validated=new Gate();
        if(consent)doAnswer(call->{validated.block();return 5L;}).when(characters).resolveDefaultSignupCharacter();
        else doAnswer(call->{validated.block();return "password-hash";}).when(passwords).protect(anyString());
        Future<Object> request=workers.submit(()->outcome(()->execute(attempt)));
        try {
            validated.awaitEntered();
            await().atMost(Duration.ofSeconds(4)).until(()->Instant.now().isAfter(expires));
            Future<?> cleanup=workers.submit(service::purgeExpiredSignupArtifacts);
            awaitDatabaseWait();validated.release();
            assertThat(request.get(15,TimeUnit.SECONDS)).isInstanceOf(SignupProofResult.class);
            cleanup.get(15,TimeUnit.SECONDS);
            assertThat(count("User")).isEqualTo(consent?1:0);
            assertThat(count("UserAgreement")).isEqualTo(consent?1:0);
            assertThat(count("SignupAuthentication")).isEqualTo(consent?0:1);
            assertThat(count("Verification")).isZero();
        } finally {validated.release();}
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void expiryDuringRowLockWaitCannotUseTheRequestStartTime(Operation operation) throws Exception {
        Attempt attempt=attempt(operation);
        Instant expires=Instant.now().plusSeconds(2);expire(attempt,expires);
        Gate locked=new Gate();
        Future<?> holder=workers.submit(()->tx.required(()->{
            if(operation==Operation.CONSENT)store.lockProof(SignupFlowService.hash(attempt.proof())).orElseThrow();
            else store.lockChallenge(attempt.challenge().id()).orElseThrow();
            try {locked.block();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            return null;
        }));
        try {
            locked.awaitEntered();
            Future<Object> request=workers.submit(()->outcome(()->execute(attempt)));
            awaitDatabaseWait();
            await().atMost(Duration.ofSeconds(4)).until(()->Instant.now().isAfter(expires));
            locked.release();holder.get(15,TimeUnit.SECONDS);
            assertThat(request.get(15,TimeUnit.SECONDS)).isEqualTo(operation==Operation.CONSENT?SignupFailure.PROOF_EXPIRED:SignupFailure.CODE_EXPIRED);
            assertThat(count("User")).isZero();
            if(operation!=Operation.CONSENT)assertThat((Instant)tx.required(()->store.lockChallenge(attempt.challenge().id()).orElseThrow().getConsumedAt())).isNull();
        } finally {locked.release();}
    }

    private Attempt attempt(Operation operation) {
        return operation==Operation.CONSENT?new Attempt(emailProof("consent@gmail.com"),null)
            :new Attempt(null,readyChallenge(operation==Operation.SOCIAL_EMAIL));
    }

    @ParameterizedTest
    @EnumSource(SocialWait.class)
    void socialProofExpiringWhileWaitingForAnotherLockCannotContinue(SocialWait waitAt) throws Exception {
        Challenge challenge=readyChallenge(true);
        Instant expires=Instant.now().plusSeconds(2);
        expire(new Attempt(challenge.proof(),null),expires);
        org.mockito.Mockito.clearInvocations(sender);
        Gate locked=new Gate();
        Future<?> holder=workers.submit(()->tx.required(()->{
            if(waitAt==SocialWait.RESEND_GUARD)
                em.createNativeQuery("SELECT bucket_key FROM signup_rate_limits WHERE bucket_key='guard' FOR UPDATE").getSingleResult();
            else store.lockChallenge(challenge.id()).orElseThrow();
            try {locked.block();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            return null;
        }));
        try {
            locked.awaitEntered();
            Future<Object> request=workers.submit(()->outcome(()->waitAt==SocialWait.VERIFY_CHALLENGE?verify(challenge,"123456"):resend(challenge)));
            awaitDatabaseWait();
            await().atMost(Duration.ofSeconds(4)).until(()->Instant.now().isAfter(expires));
            locked.release();holder.get(15,TimeUnit.SECONDS);
            assertThat(request.get(15,TimeUnit.SECONDS)).isEqualTo(SignupFailure.PROOF_EXPIRED);
            org.mockito.Mockito.verifyNoInteractions(sender);
            assertThat((String)tx.required(()->store.lockProof(SignupFlowService.hash(challenge.proof())).orElseThrow().getVerifiedEmail())).isNull();
            assertThat((Instant)tx.required(()->store.lockChallenge(challenge.id()).orElseThrow().getConsumedAt())).isNull();
            assertThat(count("User")).isZero();
        } finally {locked.release();}
    }

    @Test void cleanupWaitingOnResendKeepsTheRenewedChallenge() throws Exception {
        Challenge challenge=readyChallenge(false);
        expire(new Attempt(null,challenge),Instant.now().minusSeconds(1));
        Gate renewed=new Gate();
        doAnswer(call->{Object result=call.callRealMethod();renewed.block();return result;}).when(observed).saveChallenge(any());
        Future<Object> resend=workers.submit(()->outcome(()->resend(challenge)));
        try {
            renewed.awaitEntered();
            Future<?> cleanup=workers.submit(service::purgeExpiredSignupArtifacts);
            awaitDatabaseWait();renewed.release();
            assertThat(resend.get(15,TimeUnit.SECONDS)).isInstanceOf(EmailSignupChallengeResult.class);
            cleanup.get(15,TimeUnit.SECONDS);
            assertThat(count("Verification")).isEqualTo(1);
            assertThat(verify(challenge,"123456").progress().nextAction()).isEqualTo(SignupNextAction.AGREEMENTS);
        } finally {renewed.release();}
    }

    @Test void resendWaitingForRateGuardStartsItsCooldownAtReservationTime() throws Exception {
        Challenge challenge=readyChallenge(false);
        Gate locked=new Gate();
        Future<?> holder=workers.submit(()->tx.required(()->{
            em.createNativeQuery("SELECT bucket_key FROM signup_rate_limits WHERE bucket_key='guard' FOR UPDATE").getSingleResult();
            try {locked.block();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            return null;
        }));
        try {
            locked.awaitEntered();
            Future<EmailSignupChallengeResult> resend=workers.submit(()->resend(challenge));
            awaitDatabaseWait();Instant releasedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            locked.release();holder.get(15,TimeUnit.SECONDS);
            var result=resend.get(15,TimeUnit.SECONDS);
            Instant sentAt=tx.required(()->store.lockChallenge(challenge.id()).orElseThrow().getSentAt());
            assertThat(sentAt).isAfterOrEqualTo(releasedAt);
            assertThat(result.resendAvailableAt()).isEqualTo(sentAt.plusSeconds(60));
            assertThat(result.expiresAt()).isEqualTo(sentAt.plusSeconds(300));
            Instant lastSent=tx.required(()->em.createQuery("select b.lastSentAt from SignupRateLimit b where b.bucketKey=:key",Instant.class)
                .setParameter("key","email:"+SignupFlowService.hash("code@gmail.com")).getSingleResult());
            assertThat(lastSent).isEqualTo(sentAt);
            assertThat(outcome(()->resend(challenge))).isEqualTo(SignupFailure.RATE_LIMITED);
        } finally {locked.release();}
    }
    private SignupProofResult execute(Attempt attempt) {
        return attempt.proof()!=null?service.agree(new SignupAgreementCommand(attempt.proof(),"caller",agreements))
            :verify(attempt.challenge(),"123456");
    }
    private void expire(Attempt attempt,Instant expires) {
        tx.required(()->{
            if(attempt.proof()!=null)em.createQuery("update SignupAuthentication p set p.expiresAt=:expiry where p.tokenHash=:hash")
                .setParameter("expiry",expires).setParameter("hash",SignupFlowService.hash(attempt.proof())).executeUpdate();
            else em.createQuery("update Verification v set v.expiresAt=:expiry where v.challengeId=:id")
                .setParameter("expiry",LocalDateTime.ofInstant(expires,ZoneOffset.UTC)).setParameter("id",attempt.challenge().id()).executeUpdate();
            return null;
        });
    }
    private Challenge readyChallenge(boolean social) {
        String proof=social?socialProof("Subject",null):null;
        var challenge=service.sendEmailCode(new EmailSignupChallengeCommand("code@gmail.com","caller","origin",null,proof));
        tx.required(()->{
            em.createQuery("update Verification v set v.resendAvailableAt=:past where v.challengeId=:id")
                .setParameter("past",Instant.now().minusSeconds(61)).setParameter("id",challenge.challengeId()).executeUpdate();
            em.createQuery("update SignupRateLimit b set b.lastSentAt=:past where b.bucketKey<>'guard'")
                .setParameter("past",Instant.now().minusSeconds(61)).executeUpdate();return null;
        });
        return new Challenge(challenge.challengeId(),proof);
    }
    private SignupProofResult verify(Challenge challenge,String code) {
        return challenge.proof()==null?service.authenticateEmail(new EmailSignupAuthenticationCommand(challenge.id(),"code@gmail.com",code,"Password!","caller"))
            :service.verifySocialEmail(new SocialSignupEmailCommand(challenge.proof(),challenge.id(),"code@gmail.com",code,"caller"));
    }
    private EmailSignupChallengeResult resend(Challenge challenge) {
        return service.sendEmailCode(new EmailSignupChallengeCommand("code@gmail.com","caller","origin",challenge.id(),challenge.proof()));
    }
    private String socialProof(String subject,String email) {
        return service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE",subject,email,true,true,"caller",null)).proof();
    }
    private void awaitDatabaseWait() {
        await().atMost(Duration.ofSeconds(8)).until(()->observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0);
    }
    private Object outcome(Supplier<?> request) {
        try{return request.get();}catch(SignupFlowException error){return error.getReason();}
    }
    private record Challenge(String id,String proof) {}
    private record Attempt(String proof,Challenge challenge) {}
    private enum Operation { CONSENT, EMAIL, SOCIAL_EMAIL }
    private enum SocialWait { RESEND_CHALLENGE, RESEND_GUARD, VERIFY_CHALLENGE }
    private static final class Gate {
        private final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);
        void block() throws InterruptedException {entered.countDown();if(!released.await(15,TimeUnit.SECONDS))throw new IllegalStateException("gate timeout");}
        void awaitEntered() throws InterruptedException {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();}
        void release(){released.countDown();}
    }
}
