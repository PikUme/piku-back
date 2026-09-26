package com.pikume.back.user.auth.domain;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SignupChallengeTest {
 @Test void failedAttemptsPersistAndSuccessfulVerificationConsumesOnlyBoundChallenge() {
  Instant now=Instant.now();
  Verification v=Verification.signupChallenge("challenge","a@gmail.com","caller-hash",now,60);
  v.activateSignupCode("123456",now);
  assertThat(v.validateSignup("a@gmail.com","wrong-caller","123456",now,2)).isEqualTo("CHALLENGE_INVALID");
  assertThat(v.getAttempts()).isZero();
  assertThat(v.validateSignup("a@gmail.com","caller-hash","000000",now,2)).isEqualTo("CODE_MISMATCH");
  assertThat(v.getAttempts()).isEqualTo(1);
  assertThat(v.validateSignup("a@gmail.com","caller-hash","123456",now,2)).isNull();
  v.consumeSignup(now);
  assertThat(v.validateSignup("a@gmail.com","caller-hash","123456",now,2)).isEqualTo("CHALLENGE_INVALID");
 }
 @Test void challengeExpiresInFiveMinutes() {
  Instant now=Instant.now();
  Verification v=Verification.signupChallenge("challenge","a@gmail.com","caller",now,60);
  v.activateSignupCode("123456",now);
  assertThat(v.validateSignup("a@gmail.com","caller","123456",now.plusSeconds(300),2)).isEqualTo("CODE_EXPIRED");
 }

 @Test void attemptsCannotBeResetByTryingCorrectCodeAfterExhaustion() {
  Instant now=Instant.now();
  Verification v=Verification.signupChallenge("challenge","a@gmail.com","caller",now,60);
  v.activateSignupCode("123456",now);
  v.validateSignup("a@gmail.com","caller","wrong",now,1);
  assertThat(v.validateSignup("a@gmail.com","caller","123456",now,1)).isEqualTo("ATTEMPTS_EXHAUSTED");
 }
}
