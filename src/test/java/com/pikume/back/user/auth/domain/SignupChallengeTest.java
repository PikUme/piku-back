package com.pikume.back.user.auth.domain;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SignupChallengeTest {
 @Test void editingSocialEmailInvalidatesEarlierVerifiedEmailUntilNewChallengeCompletes() {
  Instant now=Instant.now();
  SignupAuthentication p=SignupAuthentication.social("token","caller","GOOGLE","subject","old@gmail.com",now);
  p.beginEmailVerification("new@gmail.com",now);
  assertThat(p.getVerifiedEmail()).isNull();
  assertThatThrownBy(p::requireConsentReady).isInstanceOf(com.pikume.back.user.auth.domain.exception.SignupProofException.class);
  p.verifyEmail("new@gmail.com",now);
  assertThat(p.getVerifiedEmail()).isEqualTo("new@gmail.com");
 }

 @Test void failedAttemptsPersistAndSuccessfulVerificationConsumesOnlyBoundChallenge() {
  Instant now=Instant.now();
  Verification v=Verification.signupChallenge("challenge","a@gmail.com","caller-hash",null,now,60);
  v.activateSignupCode("123456",now);
  assertThat(v.validateSignup("a@gmail.com","wrong-caller",null,"123456",now,2)).isEqualTo("CHALLENGE_INVALID");
  assertThat(v.getAttempts()).isZero();
  assertThat(v.validateSignup("a@gmail.com","caller-hash",null,"000000",now,2)).isEqualTo("CODE_MISMATCH");
  assertThat(v.getAttempts()).isEqualTo(1);
  assertThat(v.validateSignup("a@gmail.com","caller-hash",null,"123456",now,2)).isNull();
  v.consumeSignup(now);
  assertThat(v.validateSignup("a@gmail.com","caller-hash",null,"123456",now,2)).isEqualTo("CHALLENGE_INVALID");
 }
 @Test void challengeExpiresInFiveMinutesAndBoundProofCannotBeSubstituted() {
  Instant now=Instant.now();
  Verification v=Verification.signupChallenge("challenge","a@gmail.com","caller","proof-a",now,60);
  v.activateSignupCode("123456",now);
  assertThat(v.validateSignup("a@gmail.com","caller","proof-b","123456",now,2)).isEqualTo("CHALLENGE_INVALID");
  assertThat(v.validateSignup("a@gmail.com","caller","proof-a","123456",now.plusSeconds(300),2)).isEqualTo("CODE_EXPIRED");
 }
 @Test void attemptsCannotBeResetByTryingCorrectCodeAfterExhaustion() {
  Instant now=Instant.now();
  Verification v=Verification.signupChallenge("challenge","a@gmail.com","caller",null,now,60);
  v.activateSignupCode("123456",now);
  v.validateSignup("a@gmail.com","caller",null,"wrong",now,1);
  assertThat(v.validateSignup("a@gmail.com","caller",null,"123456",now,1)).isEqualTo("ATTEMPTS_EXHAUSTED");
 }
}
