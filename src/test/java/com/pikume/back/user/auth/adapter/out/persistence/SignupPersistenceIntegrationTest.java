package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.in.*;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.application.service.SignupFlowService;
import com.pikume.back.user.auth.domain.*;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.service.PasswordPolicy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignupPersistenceIntegrationTest extends SignupPersistenceTestSupport {
 @Test void failedCodeAttemptsCommitDespitePublicExceptionAndCorrectCodeThenCannotBypassLimit() {
  var c=service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null,null));
  for(int i=0;i<2;i++)assertThatThrownBy(() -> service.authenticateEmail(new EmailSignupAuthenticationCommand(c.challengeId(),"a@gmail.com","000000","Password!","caller")))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.CODE_MISMATCH);
  assertThat((Integer) tx.required(() -> store.lockChallenge(c.challengeId()).orElseThrow().getAttempts())).isEqualTo(2);
  assertThatThrownBy(() -> service.authenticateEmail(new EmailSignupAuthenticationCommand(c.challengeId(),"a@gmail.com","123456","Password!","caller")))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.ATTEMPTS_EXHAUSTED);
  assertThat(count("SignupAuthentication")).isZero();
  verify(passwords,never()).protect(anyString());
 }
 @Test void verificationConsumptionAndProofCreationAreAtomicAndCallerBound() {
  var c=service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null,null));
  assertThatThrownBy(() -> service.authenticateEmail(new EmailSignupAuthenticationCommand(c.challengeId(),"a@gmail.com","123456","Password!","other"))).isInstanceOf(SignupFlowException.class);
  var proof=service.authenticateEmail(new EmailSignupAuthenticationCommand(c.challengeId(),"a@gmail.com","123456","Password!","caller"));
  assertThat(proof.progress().nextAction()).isEqualTo(SignupNextAction.AGREEMENTS);
  assertThat(count("User")).isZero();assertThat(count("SignupAuthentication")).isEqualTo(1);
  assertThatThrownBy(() -> service.authenticateEmail(new EmailSignupAuthenticationCommand(c.challengeId(),"a@gmail.com","123456","Password!","caller"))).isInstanceOf(SignupFlowException.class);
 }
 @Test void changingChallengeIdentifierOrOriginCannotBypassEmailResendCooldown() {
  service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null,null));
  assertThatThrownBy(() -> service.sendEmailCode(new EmailSignupChallengeCommand("A@gmail.com","caller","other-origin",null,null)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.RATE_LIMITED);
  verify(sender,times(1)).issueVerificationEmail(anyString());
 }
 @Test void failedMailLeavesReservedRateButUnusableChallenge() {
  when(sender.issueVerificationEmail(anyString())).thenThrow(new IllegalStateException("delivery failed"));
  assertThatThrownBy(() -> service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null,null)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.EMAIL_SEND_FAILED);
  assertThat(count("Verification")).isEqualTo(1);
  assertThat((Instant) tx.required(() -> em.createQuery("select v from Verification v",Verification.class).getSingleResult().getDeliveryCompletedAt())).isNull();
 }
 @Test void consentCommitsUserAgreementAndConsumedProofTogetherAndReplayUsesOriginalUser() {
  String raw=emailProof("new@gmail.com");
  SignupAgreementCommand command=new SignupAgreementCommand(raw,"caller",agreements);
  var result=service.agree(command);var replay=service.agree(command);
  assertThat(replay.progress().userId()).isEqualTo(result.progress().userId());
  assertThat(count("User")).isEqualTo(1);assertThat(count("UserAgreement")).isEqualTo(1);
  assertThat((String) tx.required(() -> store.lockProof(SignupFlowService.hash(raw)).orElseThrow().getPasswordHash())).isNull();
  assertThatThrownBy(() -> service.agree(new SignupAgreementCommand(raw,"caller",List.of(new AgreementAcceptance("TERMS","v1",false)))))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.PROOF_ALREADY_USED);
 }
 @Test void lateAgreementWriteFailureRollsBackUserAndKeepsProofReusable() {
  String raw=emailProof("new@gmail.com");
  when(policy.agreements()).thenReturn(List.of(new SignupAgreementDocument("TERMS","v1",null,true)));
  assertThatThrownBy(() -> service.agree(new SignupAgreementCommand(raw,"caller",agreements))).isInstanceOf(RuntimeException.class);
  assertThat(count("User")).isZero();assertThat(count("UserAgreement")).isZero();
  assertThat((Instant) tx.required(() -> store.lockProof(SignupFlowService.hash(raw)).orElseThrow().getConsumedAt())).isNull();
 }
 @Test void parallelDistinctEmailProofsNeverRecoverByEmailEquality() throws Exception {
  String a=emailProof("same@gmail.com"),b=emailProof("same@gmail.com");
  var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
  try {
   List<Future<Object>> results=new ArrayList<>();
   for(String raw:List.of(a,b))results.add(pool.submit(() -> {ready.countDown();start.await();try{return service.agree(new SignupAgreementCommand(raw,"caller",agreements));}catch(SignupFlowException e){return e.getReason();}}));
   assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
   List<Object> values=List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));
   assertThat(values.stream().filter(SignupProofResult.class::isInstance)).hasSize(1);
   assertThat(values).contains(SignupFailure.EMAIL_ALREADY_REGISTERED);assertThat(count("User")).isEqualTo(1);
  } finally {pool.shutdownNow();}
 }
 @Test void parallelSocialProofsRecoverOnlyExactLinkedSubject() throws Exception {
  var a=service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","Subject","same@gmail.com",true,true,"caller",null));
  var b=service.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE","Subject","different@gmail.com",true,true,"caller",null));
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try {
   var first=pool.submit(() -> {start.await();return service.agree(new SignupAgreementCommand(a.proof(),"caller",agreements));});
   var second=pool.submit(() -> {start.await();return service.agree(new SignupAgreementCommand(b.proof(),"caller",agreements));});
   start.countDown();assertThat(first.get(10,TimeUnit.SECONDS).progress().userId()).isEqualTo(second.get(10,TimeUnit.SECONDS).progress().userId());
   assertThat(count("User")).isEqualTo(1);assertThat(count("UserOAuthAccount")).isEqualTo(1);assertThat(count("UserAgreement")).isEqualTo(1);
  } finally {pool.shutdownNow();}
 }
 @Test void cleanupPurgesExpiredProofAndChallengeButPreservesAbandonedUser() {
  tx.required(() -> {
   store.saveProof(SignupAuthentication.email(SignupFlowService.hash("expired"),SignupFlowService.hash("caller"),"a@gmail.com","hash",Instant.now().minusSeconds(601)));
   store.saveChallenge(Verification.signupChallenge("expired-challenge","a@gmail.com","caller",null,Instant.now().minusSeconds(301),60));
   store.createUser(User.pending("pending@gmail.com",null,"가입대기_keep",5L));return null;
  });
  service.purgeExpiredSignupArtifacts();
  assertThat(count("SignupAuthentication")).isZero();assertThat(count("Verification")).isZero();assertThat(count("User")).isEqualTo(1);
 }
 @Test void legacyCompletionRequiresBoundLegacyProofAndReplaysWithoutCallingWriterAgain() {
  when(policy.enabled()).thenReturn(false);when(policy.legacySignupEnabled()).thenReturn(true);
  var proof=service.issueLegacyProof("legacy@gmail.com","caller");
  var command=new SignUpCommand("legacy@gmail.com","Password!","legacy",5L);
  doAnswer(invocation -> {store.createUser(new User("legacy@gmail.com","hash","legacy",5L));return null;}).when(legacy).signUp(command);
  assertThatThrownBy(() -> service.completeLegacy(command,proof.proof(),"other-caller")).isInstanceOf(SignupFlowException.class);
  service.completeLegacy(command,proof.proof(),"caller");service.completeLegacy(command,proof.proof(),"caller");
  verify(legacy,times(1)).signUp(command);assertThat(count("UserAgreement")).isZero();assertThat(count("User")).isEqualTo(1);
 }
 @Test void expiredConsumedProofCannotRecoverAnExistingUser() {
  String raw=emailProof("expired@gmail.com");
  service.agree(new SignupAgreementCommand(raw,"caller",agreements));
  tx.required(() -> {em.createQuery("update SignupAuthentication p set p.expiresAt=:expiry where p.tokenHash=:hash")
    .setParameter("expiry",Instant.now().minusSeconds(1)).setParameter("hash",SignupFlowService.hash(raw)).executeUpdate();return null;});
  assertThatThrownBy(() -> service.agree(new SignupAgreementCommand(raw,"caller",agreements)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.PROOF_EXPIRED);
  assertThat(count("User")).isEqualTo(1);
 }
 @Test void cleanupCannotRemoveARecentSendCooldownAtHourlyWindowBoundary() {
  Instant now=Instant.now();
  tx.required(() -> {
   var bucket=new SignupRateLimit("email:"+SignupFlowService.hash("a@gmail.com"),now.minusSeconds(3601));
   bucket.increment(now);em.persist(bucket);return null;
  });
  service.purgeExpiredSignupArtifacts();
  assertThatThrownBy(() -> service.sendEmailCode(new EmailSignupChallengeCommand("a@gmail.com","caller","origin",null,null)))
    .isInstanceOf(SignupFlowException.class).extracting("reason").isEqualTo(SignupFailure.RATE_LIMITED);
 }
}
