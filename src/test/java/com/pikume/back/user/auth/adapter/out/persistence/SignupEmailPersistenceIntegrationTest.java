package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.application.service.SignupEmailService;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({SignupPersistenceAdapter.class, SignupTransactionAdapter.class, SignupEmailService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class SignupEmailPersistenceIntegrationTest {
 @Autowired SignupEmailService service;
 @Autowired SignupStorePort store;
 @Autowired SignupTransactionPort tx;
 @Autowired EntityManager em;
 @Autowired VerificationJpaRepository verifications;
 @MockitoBean SignupPolicyPort policy;
 @MockitoBean IssueVerificationEmailPort sender;
 @MockitoBean QueryAllowedEmailUseCase allowed;

 @BeforeEach void setup() {
  tx.required(() -> {
   em.createQuery("delete from Verification").executeUpdate();
   em.createQuery("delete from SignupRateLimit").executeUpdate();
   em.persist(new SignupRateLimit("guard",Instant.EPOCH));return null;
  });
  when(policy.resendSeconds()).thenReturn(60);
  when(policy.emailHourlyLimit()).thenReturn(5);when(policy.originHourlyLimit()).thenReturn(30);
  when(allowed.isEmailAllowed(anyString())).thenReturn(true);
  when(sender.issueVerificationEmail(anyString())).thenReturn("123456");
 }
 long count(String entity) {
  return tx.required(() -> em.createQuery("select count(e) from "+entity+" e",Long.class).getSingleResult());
 }

 @Test void changingChallengeIdentifierOrOriginCannotBypassEmailResendCooldown() {
  service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null));
  assertThatThrownBy(() -> service.sendEmailCode(new EmailSignupChallengeCommand("A@gmail.com","caller","other-origin",null)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.RATE_LIMITED);
  verify(sender,times(1)).issueVerificationEmail(anyString());
 }

 @Test void failedMailLeavesReservedRateButUnusableChallenge() {
  when(sender.issueVerificationEmail(anyString())).thenThrow(new IllegalStateException("delivery failed"));
  assertThatThrownBy(() -> service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.EMAIL_SEND_FAILED);
  assertThat(count("Verification")).isEqualTo(1);
  assertThat((Instant) tx.required(() -> em.createQuery("select v from Verification v",Verification.class).getSingleResult().getDeliveryCompletedAt())).isNull();
 }

 @Test void cleanupCannotRemoveARecentSendCooldownAtHourlyWindowBoundary() {
  Instant now=Instant.now();
  tx.required(() -> {
   var bucket=new SignupRateLimit("email:"+SignupEmailService.hash("a@gmail.com"),now.minusSeconds(3601));
   bucket.increment(now);em.persist(bucket);return null;
  });
  store.purgeExpired(Instant.now());
  assertThatThrownBy(() -> service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.RATE_LIMITED);
 }

 @Test void deliveredCodeIsHashedAndBoundToTheCallerUntilFiveMinuteExpiry() {
  var result=service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null));
  tx.required(()->{
   Verification challenge=store.lockChallenge(result.challengeId()).orElseThrow();
   assertThat(challenge.getCode()).isNotEqualTo("123456");
   assertThat(challenge.validateSignup("a@gmail.com",SignupEmailService.hash("other"),"123456",Instant.now(),2))
    .isEqualTo("CHALLENGE_INVALID");
   assertThat(challenge.validateSignup("a@gmail.com",SignupEmailService.hash("caller"),"123456",Instant.now(),2)).isNull();
   assertThat(result.expiresAt()).isEqualTo(challenge.getSentAt().plusSeconds(300));
   assertThat(result.resendAvailableAt()).isEqualTo(challenge.getSentAt().plusSeconds(60));
   return null;
  });
 }

 @Test void challengeRowsDoNotShadowLegacySignupOrPasswordResetVerification() {
  service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null));
  tx.required(()->{
   em.persist(new Verification("a@gmail.com","654321",VerificationType.SIGN_UP,LocalDateTime.now().plusMinutes(5)));
   em.persist(new Verification("a@gmail.com","777777",VerificationType.PASSWORD_RESET,LocalDateTime.now().plusMinutes(5)));
   return null;
  });
  assertThat(verifications.findByEmailAndType("a@gmail.com",VerificationType.SIGN_UP).orElseThrow().getCode()).isEqualTo("654321");
  assertThat(verifications.findByEmailAndType("a@gmail.com",VerificationType.PASSWORD_RESET).orElseThrow().getCode()).isEqualTo("777777");
 }

 @Test void cleanupPreservesLegacyVerificationWhileRemovingExpiredChallenges() {
  tx.required(()->{
   store.saveChallenge(Verification.signupChallenge("expired","a@gmail.com","caller",Instant.now().minusSeconds(301),60));
   em.persist(new Verification("legacy@gmail.com","123456",VerificationType.SIGN_UP,LocalDateTime.now().minusMinutes(1)));
   return null;
  });
  store.purgeExpired(Instant.now());
  assertThat(count("Verification")).isEqualTo(1);
  assertThat(verifications.findByEmailAndType("legacy@gmail.com",VerificationType.SIGN_UP)).isPresent();
 }
}
