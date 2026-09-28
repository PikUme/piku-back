package com.pikume.back.user.auth.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.testsupport.FixedCharacterCatalogIsolationConfiguration;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationTransactionPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import com.pikume.back.character.domain.Character;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:email-verification-http;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(FixedCharacterCatalogIsolationConfiguration.class)
class EmailVerificationHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailVerificationTransactionPort transactions;
    @Autowired EmailVerificationStorePort store;
    @MockitoBean IssueVerificationEmailPort mail;
    long characterId;

    @BeforeEach void setup() {
        characterId=transactions.required(()->{
            jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_write_mutex (id INT PRIMARY KEY)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_holds (nickname VARCHAR(255) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, expires_at TIMESTAMP(6) NOT NULL)");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM nickname_write_mutex",Integer.class)==0) jdbc.update("INSERT INTO nickname_write_mutex VALUES (1)");
            jdbc.update("DELETE FROM nickname_holds");
            em.createQuery("delete from Verification").executeUpdate();
            em.createQuery("delete from VerifiedEmail").executeUpdate();
            em.createQuery("delete from EmailVerificationRateLimit").executeUpdate();
            em.createQuery("delete from AllowedEmail").executeUpdate();
            em.createQuery("delete from User").executeUpdate();
            em.createQuery("delete from Character").executeUpdate();
            jdbc.update("INSERT INTO email_verification_rate_limits (bucket_key,window_started_at,send_count) VALUES ('guard',?,0)", java.sql.Timestamp.from(Instant.EPOCH));
            jdbc.update("INSERT INTO allowed_email (domain) VALUES ('gmail.com')");
            var character=Character.fixed("https://example.com/fixed.webp");em.persist(character);em.flush();
            return character.getId();
        });
        when(mail.issueVerificationEmail(anyString())).thenReturn("123456");
    }

    ResultActions request(String path, Object body) throws Exception {
        return mvc.perform(post("/api/auth"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    void send(String email) throws Exception {
        request("/send-verification/sign-up",Map.of("email",email)).andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty()).andExpect(jsonPath("$.resendAvailableAt").isNotEmpty())
                .andExpect(jsonPath("$.emailVerificationId").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"));
    }
    ResultActions verify(String email,String code) throws Exception {
        return request("/verify-code",Map.of("email",email,"code",code,"type","SIGN_UP"));
    }
    String proof(String email) throws Exception {
        send(email);
        var response=verify(email,"123456").andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty()).andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("emailVerificationToken").asText();
    }
    ResultActions signup(String email,String token,String nickname,long selectedCharacter) throws Exception {
        return request("/signup",Map.of("email",email,"password","abc@123","nickname",nickname,
                "fixedCharacterId",selectedCharacter,"emailVerificationToken",token));
    }
    void allowResend() {
        transactions.required(() -> {
            em.createQuery("update EmailVerificationRateLimit b set b.lastSentAt=:sent where b.bucketKey<>'guard'")
                    .setParameter("sent",Instant.now().minusSeconds(61)).executeUpdate();
            return null;
        });
    }

    @Test void existingHttpFlowCreatesMemberAndConsumesOnlyTheVerifiedOwnershipToken() throws Exception {
        String token=proof("member@gmail.com");
        assertThat(jdbc.queryForObject("SELECT verification_token_hash FROM verification WHERE email=?",String.class,"member@gmail.com"))
                .isEqualTo(EmailVerificationService.hash(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isZero();
        verify("member@gmail.com","123456").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERIFICATION_ALREADY_COMPLETED"));
        signup("member@gmail.com",token,"회원",characterId).andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("회원가입 성공"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isEqualTo(1);
        signup("member@gmail.com",token,"재시도",characterId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOKEN_ALREADY_USED"));
    }
    @Test void signupRequiresProofOwnedByTheSameEmail() throws Exception {
        String token=proof("member@gmail.com");
        request("/signup",Map.of("email","member@gmail.com","password","abc@123","nickname","회원","fixedCharacterId",characterId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.emailVerificationToken").exists());
        signup("other@gmail.com",token,"회원",characterId).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        signup("member@gmail.com","forged-token","회원",characterId).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isZero();
    }
    @Test void rejectedSignupPreservesProofForRetry() throws Exception {
        String token=proof("member@gmail.com");
        signup("member@gmail.com",token,"회원",999999).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM verification WHERE email=?",java.sql.Timestamp.class,"member@gmail.com")).isNull();
        signup("member@gmail.com",token,"회원",characterId).andExpect(status().isCreated());
    }
    @Test void incorrectCodesConsumeAttemptsAcrossRequests() throws Exception {
        send("member@gmail.com");
        for(int attempt=0;attempt<5;attempt++) verify("member@gmail.com","000000").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_MISMATCH"));
        verify("member@gmail.com","123456").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("ATTEMPTS_EXHAUSTED"));
        assertThat(jdbc.queryForObject("SELECT attempts FROM verification WHERE email=?",Integer.class,"member@gmail.com")).isEqualTo(5);
    }
    @Test void expiredCodeAndTokenReturnExplicitRecoveryErrors() throws Exception {
        send("member@gmail.com");
        jdbc.update("UPDATE verification SET expires_at=?",java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1),java.time.ZoneOffset.UTC));
        verify("member@gmail.com","123456").andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
        String token=proof("other@gmail.com");
        jdbc.update("UPDATE verification SET expires_at=? WHERE email=?",java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1),java.time.ZoneOffset.UTC),"other@gmail.com");
        signup("other@gmail.com",token,"회원",characterId).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }
    @Test void resendCooldownAndReplacementCodeAreEnforcedByExistingRoutes() throws Exception {
        send("member@gmail.com");
        request("/send-verification/sign-up",Map.of("email","MEMBER@gmail.com")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        allowResend();when(mail.issueVerificationEmail(anyString())).thenReturn("654321");send("member@gmail.com");
        verify("member@gmail.com","123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_MISMATCH"));
        verify("member@gmail.com","654321").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification",Integer.class)).isEqualTo(1);
    }
    @Test void cleanupKeepsVerifiedProofForTenMinutesAndResendDoesNotRevokeIt() throws Exception {
        String token=proof("member@gmail.com");
        store.purgeExpired(Instant.now().plusSeconds(301));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification",Integer.class)).isEqualTo(1);
        allowResend();when(mail.issueVerificationEmail(anyString())).thenReturn("654321");send("member@gmail.com");
        verify("member@gmail.com","123456").andExpect(status().isBadRequest());
        signup("member@gmail.com",token,"회원",characterId).andExpect(status().isCreated());
    }
    @Test void deliveryFailureDoesNotLeaveAUsableCodeAndStillLimitsRetry() throws Exception {
        when(mail.issueVerificationEmail(anyString())).thenThrow(new IllegalStateException("mail unavailable"));
        request("/send-verification/sign-up",Map.of("email","member@gmail.com")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_SEND_FAILED"));
        verify("member@gmail.com","123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERIFICATION_INVALID"));
        request("/send-verification/sign-up",Map.of("email","member@gmail.com")).andExpect(status().isTooManyRequests());
    }
    @Test void passwordResetUsesItsExistingRequestAndResponseContract() throws Exception {
        String token=proof("member@gmail.com");signup("member@gmail.com",token,"회원",characterId).andExpect(status().isCreated());
        request("/send-verification/password-reset",Map.of("email","member@gmail.com")).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("비밀번호 재설정 인증 이메일이 발송되었습니다."));
        request("/verify-code",Map.of("email","member@gmail.com","code","123456","type","PASSWORD_RESET"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("이메일 인증이 완료되었습니다."))
                .andExpect(jsonPath("$.emailVerificationToken").doesNotExist());
        request("/password-reset",Map.of("email","member@gmail.com","password","new@123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("비밀번호가 재설정되었습니다."));
    }
}
