package com.pikume.back.user.auth.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.character.domain.Character;
import com.pikume.back.testsupport.FixedCharacterCatalogIsolationConfiguration;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.port.out.RefreshSessionPort;
import com.pikume.back.user.auth.application.port.out.SignupTransactionPort;
import com.pikume.back.user.auth.domain.SignupAuthentication;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/** Exercises the screen contract through real HTTP/security, application services and DB state. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:signup-journey;DB_CLOSE_DELAY=-1",
    "signup.enabled=true", "signup.agreements[0].type=TERMS", "signup.agreements[0].version=v1",
    "signup.agreements[0].content=테스트용 필수 동의 본문", "signup.agreements[0].required=true"
})
@AutoConfigureMockMvc
@Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@Import(FixedCharacterCatalogIsolationConfiguration.class)
class SignupJourneyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired SignupTransactionPort tx;
    @MockitoBean IssueVerificationEmailPort mail;
    @MockitoBean RefreshSessionPort sessions;
    final Map<String,RefreshSessionPort.RefreshSession> storedSessions=new LinkedHashMap<>();
    long defaultCharacterId;

    @BeforeEach void prepareCatalogAndExternalServices() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_write_mutex (id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_holds (nickname VARCHAR(255) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, expires_at TIMESTAMP(6) NOT NULL)");
        jdbc.update("MERGE INTO nickname_write_mutex (id) KEY(id) VALUES (1)");
        tx.required(() -> {
            for(String entity:List.of("UserAgreement","UserOAuthAccount","SignupAuthentication","Verification","User","Character","SignupRateLimit","AllowedEmail")) {
                em.createQuery("delete from "+entity).executeUpdate();
            }
            jdbc.update("DELETE FROM nickname_holds");
            jdbc.update("INSERT INTO signup_rate_limits (bucket_key,window_started_at,send_count) VALUES ('guard',?,0)",java.sql.Timestamp.from(Instant.EPOCH));
            jdbc.update("INSERT INTO allowed_email (domain) VALUES ('gmail.com')");
            em.persist(Character.fixed("public/characters/fixed/alternative.webp"));
            Character defaultCharacter=Character.fixed("public/characters/fixed/base_image_1.webp");
            em.persist(defaultCharacter);em.flush();
            defaultCharacterId=defaultCharacter.getId();
            return null;
        });
        storedSessions.clear();
        when(mail.issueVerificationEmail(anyString())).thenReturn("123456");
        doAnswer(call -> {
            RefreshSessionPort.RefreshSession session=call.getArgument(0);
            storedSessions.put(session.refreshToken(),session);return null;
        }).when(sessions).storeSession(any());
        when(sessions.loadSessionByRefreshToken(anyString())).thenAnswer(call -> Optional.ofNullable(storedSessions.get(call.getArgument(0))));
    }

    @ParameterizedTest(name="동의 후 새로고침과 기본 캐릭터 그대로 완료: mobile={0}")
    @ValueSource(booleans={false,true})
    void consentRefreshAndUnchangedCharacterCompletionUseThePersistedSelection(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        client.request(get(client.base+"/signup/progress"),200);
        JsonNode challenge=client.request(post(client.base+"/signup/email/code").content(body(Map.of("email","journey@gmail.com"))),200);
        JsonNode authenticated=client.request(post(client.base+"/signup/email").content(body(Map.of(
            "email","journey@gmail.com","challengeId",challenge.get("challengeId").asText(),"code","123456","password","Password!1"))),200);
        assertThat(authenticated.at("/progress/nextAction").asText()).isEqualTo("AGREEMENTS");
        JsonNode documents=client.request(get(client.base+"/signup/agreements"),200);
        JsonNode consent=client.request(post(client.base+"/signup/agreements").content(body(Map.of("agreements",List.of(Map.of(
            "type",documents.get(0).get("type").asText(),"version",documents.get(0).get("version").asText(),"agreed",true))))),200);
        assertThat(consent.at("/user/characterId").asLong(-1)).isEqualTo(defaultCharacterId);
        String userId=consent.at("/user/id").asText();
        String refresh=client.refreshToken;
        client.accessToken=null; // A reload discards the in-memory access token.
        var reissue=post(client.base+"/reissue");
        if(mobile)reissue.content(body(Map.of("refreshToken",refresh)));
        client.request(reissue,200);
        JsonNode current=client.request(get(client.base+"/me"),200);
        assertThat(current.at("/user/characterId").asLong(-1)).isEqualTo(defaultCharacterId);
        assertThat(current.at("/user/id").asText()).isEqualTo(userId);
        client.request(post(client.base+"/signup/nickname").content(body(Map.of("nickname","완료닉네임"))),200);
        JsonNode complete=client.request(post(client.base+"/signup/profile").content(body(Map.of(
            "nickname","완료닉네임","characterId",current.at("/user/characterId").asLong()))),200);
        assertThat(complete.get("characterId").asLong()).isEqualTo(defaultCharacterId);
        assertThat(complete.get("profileSetupStatus").asText()).isEqualTo("COMPLETED");
        JsonNode reloaded=client.request(get(client.base+"/me"),200);
        assertThat(reloaded.at("/user/characterId").asLong()).isEqualTo(defaultCharacterId);
        assertThat(reloaded.at("/user/profileSetupStatus").asText()).isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void aNewEmailCanBeAuthenticatedWithoutWaitingForThePreviousProofToExpire(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        authenticate(client,"first@gmail.com");
        JsonNode challenge=client.request(post(client.base+"/signup/email/code").content(body(Map.of(
            "email","second@gmail.com","restartAuthentication",true))),200);
        if(mobile)client.proof=null;
        else assertThat(client.cookies).doesNotContainKey(SignupWebCredentials.PROOF);
        JsonNode replacement=client.request(post(client.base+"/signup/email").content(body(Map.of(
            "email","second@gmail.com","challengeId",challenge.get("challengeId").asText(),"code","123456","password","Password!1"))),200);
        assertThat(replacement.at("/progress/email").asText()).isEqualTo("second@gmail.com");
        assertThat(replacement.at("/progress/nextAction").asText()).isEqualTo("AGREEMENTS");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void aLegacyProofCannotTrapTheNewSignupScreenDuringRollout(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        client.request(get(client.base+"/signup/progress"),200);
        String binding=mobile?client.binding:client.cookies.get(SignupWebCredentials.BINDING).getValue();
        String raw="l".repeat(43);
        String tokenHash=hash(raw),bindingHash=hash(binding);
        tx.required(() -> {em.persist(SignupAuthentication.legacy(tokenHash,bindingHash,"legacy@gmail.com",Instant.now()));return null;});
        client.proof=raw;
        client.cookies.put(SignupWebCredentials.PROOF,new Cookie(SignupWebCredentials.PROOF,raw));
        JsonNode progress=client.request(get(client.base+"/signup/progress"),200);
        assertThat(progress.at("/progress/nextAction").asText()).isEqualTo("AUTHENTICATE");
        if(mobile)client.proof=null;
        else assertThat(client.cookies).doesNotContainKey(SignupWebCredentials.PROOF);
        client.request(post(client.base+"/signup/email/code").content(body(Map.of("email","fresh@gmail.com"))),200);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void expiredPersistedProofIsDiscardedAndTheReturnedCsrfCanStartAgain(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        authenticate(client,"expired@gmail.com");
        tx.required(() -> {em.createQuery("update SignupAuthentication p set p.expiresAt=:expiry")
            .setParameter("expiry",Instant.now().minusSeconds(1)).executeUpdate();return null;});
        JsonNode restart=client.request(get(client.base+"/signup/progress"),200);
        assertThat(restart.at("/progress/nextAction").asText()).isEqualTo("AUTHENTICATE");
        if(mobile)client.proof=null;
        else assertThat(client.cookies).doesNotContainKey(SignupWebCredentials.PROOF);
        client.request(post(client.base+"/signup/email/code").content(body(Map.of("email","fresh@gmail.com"))),200);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void lostSessionAfterConsentRequiresLoginAndRecoversTheSameMember(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        authenticate(client,"recovery@gmail.com");
        JsonNode consent=client.request(post(client.base+"/signup/agreements").content(body(Map.of("agreements",List.of(Map.of(
            "type","TERMS","version","v1","agreed",true))))),200);
        String originalId=consent.at("/user/id").asText();
        client.accessToken=null;client.refreshToken=null;client.cookies.remove("rn");storedSessions.clear();
        JsonNode progress=client.request(get(client.base+"/signup/progress"),200);
        assertThat(progress.at("/progress/nextAction").asText()).isEqualTo("AUTHENTICATE");
        assertThat(progress.at("/progress/userId").asText()).isEqualTo(originalId);
        JsonNode login=client.request(post(client.base+"/login").content(body(Map.of(
            "email","recovery@gmail.com","password","Password!1"))),200);
        assertThat(login.at("/user/id").asText()).isEqualTo(originalId);
        assertThat(login.at("/user/profileSetupStatus").asText()).isEqualTo("REQUIRED");
        JsonNode resumed=client.request(get(client.base+"/signup/progress"),200);
        assertThat(resumed.at("/progress/nextAction").asText()).isEqualTo("PROFILE");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void normalizedNicknameReservationCompletionAndRetryShareTheSameValue(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        authenticate(client,"nickname@gmail.com");
        JsonNode consent=client.request(post(client.base+"/signup/agreements").content(body(Map.of("agreements",List.of(Map.of(
            "type","TERMS","version","v1","agreed",true))))),200);
        String expected="12345678901234567890";
        JsonNode reservation=client.request(post(client.base+"/signup/nickname").content(body(Map.of("nickname","  "+expected+"　 "))),200);
        assertThat(reservation.get("nickname").asText()).isEqualTo(expected);
        JsonNode repeated=client.request(post(client.base+"/signup/nickname").content(body(Map.of("nickname","\t"+expected+"\n"))),200);
        assertThat(repeated.get("expiresAt")).isEqualTo(reservation.get("expiresAt"));
        JsonNode completed=client.request(post(client.base+"/signup/profile").content(body(Map.of("nickname"," "+expected+" ","characterId",defaultCharacterId))),200);
        JsonNode retried=client.request(post(client.base+"/signup/profile").content(body(Map.of("nickname","\t"+expected+"\n","characterId",defaultCharacterId))),200);
        assertThat(completed.get("nickname").asText()).isEqualTo(expected);
        assertThat(retried).isEqualTo(completed);
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,consent.at("/user/id").asText())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds",Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void invalidNormalizedSignupNicknamesKeepThePendingUserAndProblemContract(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        authenticate(client,"invalidnickname@gmail.com");
        JsonNode consent=client.request(post(client.base+"/signup/agreements").content(body(Map.of("agreements",List.of(Map.of(
            "type","TERMS","version","v1","agreed",true))))),200);
        for(String nickname:List.of("", "  　 ", "  가입대기_123　 ", "123456789012345678901")) {
            for(String endpoint:List.of("nickname","profile")) {
                JsonNode error=client.request(post(client.base+"/signup/"+endpoint).content(body(Map.of("nickname",nickname,"characterId",defaultCharacterId))),400);
                assertThat(error.path("code").asText()).isEqualTo("INVALID_NICKNAME");
                assertThat(error.get("type").asText()).isEqualTo("https://api.pikume.com/problems/signup/invalid-nickname");
                assertThat(error.get("detail").asText()).isNotBlank();
            }
        }
        assertThat(jdbc.queryForObject("SELECT profile_setup_status FROM users WHERE id=?",String.class,consent.at("/user/id").asText())).isEqualTo("REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds",Integer.class)).isZero();
    }

    private void authenticate(Client client,String email) throws Exception {
        client.request(get(client.base+"/signup/progress"),200);
        JsonNode challenge=client.request(post(client.base+"/signup/email/code").content(body(Map.of("email",email))),200);
        client.request(post(client.base+"/signup/email").content(body(Map.of(
            "email",email,"challengeId",challenge.get("challengeId").asText(),"code","123456","password","Password!1"))),200);
    }

    @Test
    void successfulEmailLoginAndLogoutClearProofButFailedLoginPreservesIt() throws Exception {
        Client client=new Client(false);
        authenticate(client,"member@gmail.com");
        client.request(post(client.base+"/signup/agreements").content(body(Map.of("agreements",List.of(Map.of(
            "type","TERMS","version","v1","agreed",true))))),200);
        assertThat(client.cookies).containsKey(SignupWebCredentials.PROOF);
        client.request(post(client.base+"/logout"),200);
        assertThat(client.cookies).doesNotContainKeys(SignupWebCredentials.PROOF,"rn");
        client.accessToken=null;
        authenticate(client,"different@gmail.com");
        client.request(post(client.base+"/login").content(body(Map.of("email","member@gmail.com","password","wrong"))),401);
        assertThat(client.cookies).containsKey(SignupWebCredentials.PROOF);
        client.request(post(client.base+"/login").content(body(Map.of("email","member@gmail.com","password","Password!1"))),200);
        assertThat(client.cookies).doesNotContainKey(SignupWebCredentials.PROOF);
        assertThat(client.cookies).containsKeys(SignupWebCredentials.CSRF,SignupWebCredentials.BINDING,"rn");
        client.request(post(client.base+"/logout"),200);
        client.accessToken=null;
        JsonNode progress=client.request(get(client.base+"/signup/progress"),200);
        assertThat(progress.at("/progress/nextAction").asText()).isEqualTo("AUTHENTICATE");
        assertThat(progress.at("/progress/email").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void lostWithdrawalResponseCannotTrapTheClientWithAConsumedProof(boolean mobile) throws Exception {
        Client client=new Client(mobile);
        authenticate(client,"withdrawn@gmail.com");
        client.request(post(client.base+"/signup/agreements").content(body(Map.of("agreements",List.of(Map.of(
            "type","TERMS","version","v1","agreed",true))))),200);
        var previousCookies=new LinkedHashMap<>(client.cookies);
        client.request(delete(client.base+"/signup/profile"),204);
        client.cookies.clear();client.cookies.putAll(previousCookies); // The withdrawal response was lost.
        client.accessToken=null;client.refreshToken=null;client.cookies.remove("rn");
        JsonNode progress=client.request(get(client.base+"/signup/progress"),200);
        assertThat(progress.at("/progress/nextAction").asText()).isEqualTo("AUTHENTICATE");
        assertThat(progress.at("/progress/userId").isNull()).isTrue();
        if(mobile)client.proof=null;
        else assertThat(client.cookies).doesNotContainKey(SignupWebCredentials.PROOF);
        client.request(post(client.base+"/signup/email/code").content(body(Map.of("email","fresh@gmail.com"))),200);
    }

    private String hash(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String body(Object value) throws Exception {return json.writeValueAsString(value);}

    private class Client {
        final boolean mobile;
        final String base;
        final Map<String,Cookie> cookies=new LinkedHashMap<>();
        String binding,csrf,proof,accessToken,refreshToken;
        Client(boolean mobile) {this.mobile=mobile;this.base=mobile?"/api/mobile/auth":"/api/auth";}
        JsonNode request(MockHttpServletRequestBuilder request,int expectedStatus) throws Exception {
            request.contentType(MediaType.APPLICATION_JSON).header("Device-Id","journey-device");
            if(accessToken!=null)request.header("Authorization",accessToken);
            if(mobile) {
                if(binding!=null)request.header("X-Signup-Binding",binding);
                if(proof!=null)request.header("X-Signup-Proof",proof);
            } else {
                request.header("Origin","https://www.pikume.com");
                if(csrf!=null)request.header("X-Signup-CSRF",csrf);
                if(!cookies.isEmpty())request.cookie(cookies.values().toArray(Cookie[]::new));
            }
            MockHttpServletResponse response=mvc.perform(request).andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
            for(Cookie cookie:response.getCookies()) {
                if(cookie.getMaxAge()==0)cookies.remove(cookie.getName());
                else cookies.put(cookie.getName(),cookie);
            }
            if(response.getContentAsString().isBlank())return json.createObjectNode();
            JsonNode result=json.readTree(response.getContentAsString());
            if(result.has("csrfToken"))csrf=result.get("csrfToken").asText();
            if(result.has("callerBinding"))binding=result.get("callerBinding").asText();
            if(result.has("proof"))proof=result.get("proof").asText();
            if(result.has("tokens")) {
                accessToken="Bearer "+result.at("/tokens/accessToken").asText();
                refreshToken=result.at("/tokens/refreshToken").asText();
            }
            if(response.getHeader("Authorization")!=null)accessToken=response.getHeader("Authorization");
            return result;
        }
    }
}
