package com.pikume.back.user.auth.domain;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EmailVerificationTest {
 @Test void codeAndOwnershipProofHaveSeparateExpiryAndSingleConsumption() {
  Instant now=Instant.now();
  var v=Verification.emailVerification("id","a@gmail.com",now,60);
  v.activateCode("123456",now);
  assertThat(v.validateCode("000000",now,5)).isEqualTo("CODE_MISMATCH");
  assertThat(v.getAttempts()).isEqualTo(1);
  assertThat(v.validateCode("123456",now.plusSeconds(299),5)).isNull();
  v.verify("token-hash",now.plusSeconds(299));
  assertThat(v.validateCode("123456",now.plusSeconds(300),5)).isEqualTo("VERIFICATION_ALREADY_COMPLETED");
  assertThat(v.validateToken(now.plusSeconds(898))).isNull();
  assertThat(v.validateToken(now.plusSeconds(899))).isEqualTo("TOKEN_EXPIRED");
  v.consumeVerifiedEmail(now.plusSeconds(301));
  assertThat(v.validateToken(now.plusSeconds(302))).isEqualTo("TOKEN_ALREADY_USED");
 }
 @Test void codeExpiresAtFiveMinuteBoundary() {
  Instant now=Instant.now();
  var v=Verification.emailVerification("id","a@gmail.com",now,60);v.activateCode("123456",now);
  assertThat(v.validateCode("123456",now.plusSeconds(300),5)).isEqualTo("CODE_EXPIRED");
 }
 @Test void correctCodeCannotBypassExhaustedAttempts() {
  Instant now=Instant.now();
  var v=Verification.emailVerification("id","a@gmail.com",now,60);v.activateCode("123456",now);
  v.validateCode("wrong",now,1);
  assertThat(v.validateCode("123456",now,1)).isEqualTo("ATTEMPTS_EXHAUSTED");
 }
}
