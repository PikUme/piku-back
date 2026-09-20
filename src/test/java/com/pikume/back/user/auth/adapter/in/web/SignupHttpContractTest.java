package com.pikume.back.user.auth.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.security.adapter.in.web.AuthUserResponseMapper;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.port.in.*;
import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.port.in.*;
import com.pikume.back.user.auth.application.exception.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfiguration;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SignupHttpContractTest {
    SignupFlowUseCase flow = mock(SignupFlowUseCase.class);
    QuerySignupConfigurationUseCase config = mock(QuerySignupConfigurationUseCase.class);
    IssueUserSessionUseCase issue = mock(IssueUserSessionUseCase.class);
    LegacySignupProofUseCase legacy = mock(LegacySignupProofUseCase.class);
    VerifyEmailUseCase verify = mock(VerifyEmailUseCase.class);
    GoogleAuthenticationUseCase google = mock(GoogleAuthenticationUseCase.class);
    QueryUserAccessUseCase users = mock(QueryUserAccessUseCase.class);
    MockMvc mvc;
    static final String BINDING="b".repeat(43), CSRF="c".repeat(43), PROOF="p".repeat(43);
    @BeforeEach void setup() {
        var credentials = new SignupWebCredentials(request -> {var cors=new CorsConfiguration();cors.setAllowedOrigins(List.of("https://www.pikume.com"));return cors;});
        var sessions = new SignupSessionResponseWriter(issue,new AuthUserResponseMapper((value,accessible) -> "https://assets.example/"+value),credentials);
        var controller = new SignupController(flow,mock(QuerySignupAgreementUseCase.class),config,users,mock(ReserveSignupNicknameUseCase.class),mock(CompleteSignupProfileUseCase.class),mock(WithdrawPendingSignupUseCase.class),credentials,sessions);
        var old = new AuthController(legacy,verify,mock(ResetPasswordUseCase.class),mock(QueryAllowedEmailUseCase.class),config,credentials);
        var settings = new SignupWebSettings(); settings.setCompletionUri("https://www.pikume.com/auth/complete");
        mvc=MockMvcBuilders.standaloneSetup(controller,old,new GoogleAuthenticationController(google,credentials,sessions,settings))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new SignupExceptionHandler(new ProblemDetailFactory())).build();
    }
    @AfterEach void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    @ParameterizedTest
    @EnumSource(value=SignupFailure.class,names={"PROOF_EXPIRED","PROOF_INVALID","FLOW_MISMATCH"})
    void unusableProofCanRestartWithCsrfAndWithoutTheStaleHttpOnlyCookie(SignupFailure failure) throws Exception {
        given(config.querySignupConfiguration()).willReturn(new SignupConfiguration(true,false));
        given(flow.progress(eq(PROOF),anyString())).willThrow(new SignupFlowException(failure));
        mvc.perform(get("/api/auth/signup/progress").cookie(new Cookie(SignupWebCredentials.PROOF,PROOF)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.progress.nextAction").value("AUTHENTICATE"))
            .andExpect(jsonPath("$.csrfToken").isNotEmpty()).andExpect(cookie().maxAge(SignupWebCredentials.PROOF,0))
            .andExpect(cookie().doesNotExist("rn"));
    }
    @Test void malformedProofCookieIsRemovedEvenWhenThereIsNoUsableProofToLookUp() throws Exception {
        given(config.querySignupConfiguration()).willReturn(new SignupConfiguration(true,false));
        given(flow.progress(isNull(),anyString())).willReturn(new SignupProgress(SignupNextAction.AUTHENTICATE,null,null,null,null));
        mvc.perform(get("/api/auth/signup/progress").cookie(new Cookie(SignupWebCredentials.PROOF,"malformed")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.csrfToken").isNotEmpty())
            .andExpect(cookie().maxAge(SignupWebCredentials.PROOF,0));
    }
    @Test void storageOutageDoesNotMasqueradeAsAnAnonymousSignupRestart() {
        given(flow.progress(eq(PROOF),anyString())).willThrow(new org.springframework.dao.DataAccessResourceFailureException("storage unavailable"));
        assertThatThrownBy(() -> mvc.perform(get("/api/auth/signup/progress").cookie(cookies())))
            .hasRootCauseInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"REQUIRED","COMPLETED"})
    void authenticatedMemberProgressDoesNotResetBecauseOfAnOldProofCookie(String profileStatus) throws Exception {
        var principal=com.pikume.back.security.principal.UserPrincipal.withProfileState("user","nick",null,profileStatus);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        given(config.querySignupConfiguration()).willReturn(new SignupConfiguration(true,false));
        given(users.queryUserAccess("user")).willReturn(java.util.Optional.of(new com.pikume.back.user.application.dto.UserAccessView(
            "user",false,com.pikume.back.user.application.dto.UserAccessProfileStatus.valueOf(profileStatus))));
        mvc.perform(get("/api/auth/signup/progress").cookie(cookies()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.progress.userId").value("user"))
            .andExpect(jsonPath("$.progress.nextAction").value("REQUIRED".equals(profileStatus)?"PROFILE":"COMPLETE"))
            .andExpect(jsonPath("$.csrfToken").value(CSRF)).andExpect(cookie().doesNotExist("rn"));
    }
    @Test void mobileExpiredProofReturnsRestartStateAndCallerWithoutWebCookies() throws Exception {
        given(config.querySignupConfiguration()).willReturn(new SignupConfiguration(true,false));
        given(flow.progress(PROOF,BINDING)).willThrow(new SignupFlowException(SignupFailure.PROOF_EXPIRED));
        mvc.perform(get("/api/mobile/auth/signup/progress").header("X-Signup-Binding",BINDING).header("X-Signup-Proof",PROOF))
            .andExpect(status().isOk()).andExpect(jsonPath("$.progress.nextAction").value("AUTHENTICATE"))
            .andExpect(jsonPath("$.callerBinding").value(BINDING)).andExpect(jsonPath("$.csrfToken").doesNotExist())
            .andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void expiredProofStillRejectsConsentWrites() throws Exception {
        given(flow.agree(any())).willThrow(new SignupFlowException(SignupFailure.PROOF_EXPIRED));
        mvc.perform(post("/api/auth/signup/agreements").header("Origin","https://www.pikume.com")
            .header("X-Signup-CSRF",CSRF).header("Device-Id","device").cookie(cookies())
            .contentType(MediaType.APPLICATION_JSON).content("{\"agreements\":[{\"type\":\"TERMS\",\"version\":\"v1\",\"agreed\":true}]}"))
            .andExpect(status().isGone()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(410)).andExpect(jsonPath("$.detail").isString())
            .andExpect(jsonPath("$.code").value("PROOF_EXPIRED"));
    }
    @Test void unsuccessfulAuthenticationRestartKeepsThePreviousProof() throws Exception {
        given(flow.sendEmailCode(any())).willThrow(new SignupFlowException(SignupFailure.RATE_LIMITED));
        mvc.perform(post("/api/auth/signup/email/code").header("Origin","https://www.pikume.com")
            .header("X-Signup-CSRF",CSRF).cookie(cookies()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"new@gmail.com\",\"restartAuthentication\":true}"))
            .andExpect(status().isTooManyRequests()).andExpect(cookie().doesNotExist(SignupWebCredentials.PROOF))
            .andExpect(jsonPath("$.status").value(429)).andExpect(jsonPath("$.detail").isString());
        verify(flow).sendEmailCode(argThat(command -> command.signupProof()==null && command.challengeId()==null));
    }
    @Test void resumedProofCookieCannotOutliveTheOriginalAuthenticationDeadline() throws Exception {
        given(flow.verifySocialEmail(any())).willReturn(new SignupProofResult(PROOF,new SignupProgress(
            SignupNextAction.AGREEMENTS,"user@gmail.com",null,null,Instant.now().plusSeconds(45))));
        var response=mvc.perform(post("/api/auth/signup/social/email").header("Origin","https://www.pikume.com")
            .header("X-Signup-CSRF",CSRF).cookie(cookies()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"challengeId\":\"challenge\",\"email\":\"user@gmail.com\",\"code\":\"123456\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getCookie(SignupWebCredentials.PROOF).getMaxAge()).isBetween(1,45);
    }
    @Test void pageEntryIssuesOnlyCookiesAndNoSignupRecord() throws Exception {
        given(config.querySignupConfiguration()).willReturn(new SignupConfiguration(true,false));
        given(flow.progress(isNull(),anyString())).willReturn(new SignupProgress(SignupNextAction.AUTHENTICATE,null,null,null,null));
        var response=mvc.perform(get("/api/auth/signup/progress")).andExpect(status().isOk())
            .andExpect(jsonPath("$.progress.nextAction").value("AUTHENTICATE")).andExpect(jsonPath("$.csrfToken").exists())
            .andExpect(jsonPath("$.callerBinding").doesNotExist()).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        verify(flow).progress(isNull(),anyString()); verifyNoMoreInteractions(flow);
    }
    @Test void webWriteRequiresCsrfBeforeApplicationCall() throws Exception {
        mvc.perform(post("/api/auth/signup/agreements").header("Origin","https://www.pikume.com")
            .contentType(MediaType.APPLICATION_JSON).content("{\"agreements\":[{\"type\":\"TERMS\",\"version\":\"v1\",\"agreed\":true}]}"))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.detail").isString()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(flow);
    }
    @Test void webProofLivesOnlyInSecureCookie() throws Exception {
        given(flow.authenticateEmail(any())).willReturn(new SignupProofResult(PROOF,new SignupProgress(SignupNextAction.AGREEMENTS,"user@gmail.com",null,null,Instant.now().plusSeconds(600))));
        var response=mvc.perform(post("/api/auth/signup/email").header("Origin","https://www.pikume.com").header("X-Signup-CSRF",CSRF)
            .cookie(cookies()).contentType(MediaType.APPLICATION_JSON).content(emailBody()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.proof").doesNotExist()).andExpect(jsonPath("$.tokens").doesNotExist())
            .andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains(SignupWebCredentials.PROOF+"="+PROOF,"HttpOnly","Secure"));
        verifyNoInteractions(issue);
    }
    @Test void mobileCannotUseWebCookiesAsProofOfCaller() throws Exception {
        mvc.perform(post("/api/mobile/auth/signup/email").cookie(cookies()).contentType(MediaType.APPLICATION_JSON).content(emailBody()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CALLER_REQUIRED"));
        verifyNoInteractions(flow);
    }
    @Test void mobileProofIsReturnedInBodyWithoutCookies() throws Exception {
        given(flow.authenticateEmail(any())).willReturn(new SignupProofResult(PROOF,new SignupProgress(SignupNextAction.AGREEMENTS,"user@gmail.com",null,null,Instant.now().plusSeconds(600))));
        mvc.perform(post("/api/mobile/auth/signup/email").header("X-Signup-Binding",BINDING).contentType(MediaType.APPLICATION_JSON).content(emailBody()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.proof").value(PROOF)).andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void agreementCompletionUsesExistingWebSessionContract() throws Exception {
        given(flow.agree(any())).willReturn(new SignupProofResult(PROOF,new SignupProgress(SignupNextAction.PROFILE,"user@gmail.com","user","REQUIRED",Instant.now().plusSeconds(500))));
        given(issue.issueSession("user","device")).willReturn(new LoginResult("access","refresh",new LoginResult.UserInfo("user","가입대기_a",new UserAvatarReference("base.webp",false,true),"REQUIRED")));
        mvc.perform(post("/api/auth/signup/agreements").header("Origin","https://www.pikume.com").header("X-Signup-CSRF",CSRF).header("Device-Id","device")
            .cookie(cookies()).contentType(MediaType.APPLICATION_JSON).content("{\"agreements\":[{\"type\":\"TERMS\",\"version\":\"v1\",\"agreed\":true}]}"))
            .andExpect(status().isOk()).andExpect(header().string("Authorization","Bearer access"))
            .andExpect(jsonPath("$.user.profileSetupStatus").value("REQUIRED")).andExpect(jsonPath("$.tokens").doesNotExist());
    }
    @Test void legacySignupClosesWhenChapteredSignupEnabled() throws Exception {
        given(config.querySignupConfiguration()).willReturn(new SignupConfiguration(true,false));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"user@gmail.com\",\"password\":\"abc@123\",\"nickname\":\"nick\",\"fixedCharacterId\":1}"))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LEGACY_SIGNUP_DISABLED"));
        verifyNoInteractions(legacy);
    }
    @Test void callbackRedirectContainsNoCredentialsAndUsesBoundDevice() throws Exception {
        given(google.completeWeb("state","code",BINDING)).willReturn(new GoogleAuthenticationResult(new SignupProofResult(PROOF,new SignupProgress(SignupNextAction.AGREEMENTS,"user@gmail.com",null,null,Instant.now().plusSeconds(600))),"bound-device"));
        mvc.perform(get("/api/auth/oauth/google/callback").param("state","state").param("code","code").cookie(cookies()))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","https://www.pikume.com/auth/complete"))
            .andExpect(header().string("Referrer-Policy","no-referrer"));
        verifyNoInteractions(issue);
    }
    @Test void existingGoogleLoginClearsAnEarlierSignupProofAfterSessionIssuance() throws Exception {
        given(google.completeWeb("state","code",BINDING)).willReturn(new GoogleAuthenticationResult(new SignupProofResult(null,
            new SignupProgress(SignupNextAction.COMPLETE,"existing@gmail.com","existing","COMPLETED",null)),"bound-device"));
        given(issue.issueSession("existing","bound-device")).willReturn(new LoginResult("access","refresh",
            new LoginResult.UserInfo("existing","existing",null,"COMPLETED")));
        mvc.perform(get("/api/auth/oauth/google/callback").param("state","state").param("code","code").cookie(cookies()))
            .andExpect(status().isSeeOther()).andExpect(cookie().maxAge(SignupWebCredentials.PROOF,0))
            .andExpect(cookie().value("rn","refresh"));
    }
    @Test void cancelledCallbackReturnsToFrontendWithBoundedPublicError() throws Exception {
        mvc.perform(get("/api/auth/oauth/google/callback").param("state","state").param("error","access_denied").cookie(cookies()))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","https://www.pikume.com/auth/complete?oauthError=GOOGLE_CANCELLED"));
        verify(google).failWeb("state",BINDING);
        verifyNoInteractions(issue);
    }
    @Test void replayCallbackReturnsToFrontendWithoutIssuingCredentials() throws Exception {
        given(google.completeWeb("state","code",BINDING)).willThrow(new com.pikume.back.user.auth.domain.exception.OAuthRequestException(com.pikume.back.user.auth.domain.exception.OAuthRequestException.Reason.REPLAY));
        mvc.perform(get("/api/auth/oauth/google/callback").param("state","state").param("code","code").cookie(cookies()))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location","https://www.pikume.com/auth/complete?oauthError=OAUTH_REPLAY"));
        verifyNoInteractions(issue);
    }
    private Cookie[] cookies() {return new Cookie[]{new Cookie(SignupWebCredentials.BINDING,BINDING),new Cookie(SignupWebCredentials.CSRF,CSRF),new Cookie(SignupWebCredentials.PROOF,PROOF)};}
    private String emailBody() {return "{\"challengeId\":\"challenge\",\"email\":\"user@gmail.com\",\"code\":\"123456\",\"password\":\"abc@123\"}";}
}
