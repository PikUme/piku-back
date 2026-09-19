package com.pikume.back.security.adapter.in.web;

import com.pikume.back.security.adapter.out.token.JwtTokenProvider;
import com.pikume.back.user.application.dto.*;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import com.pikume.back.testsupport.FixedCharacterCatalogIsolationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedCharacterCatalogIsolationConfiguration.class)
class SignupAccessSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @MockitoBean QueryUserAccessUseCase access;
    @MockitoBean QueryUserIdentityUseCase identities;

    @Test void pendingMemberCannotWriteOrStartPushAndSse() throws Exception {
        String token=pending();
        for (String path:new String[]{"/api/diary","/api/fcm"}) {
            mvc.perform(post(path).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/signup/profile-setup-required"))
                .andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.detail").isString());
        }
        mvc.perform(get("/api/sse/subscribe").header("Authorization",token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/diary/ai/status").header("Authorization",token))
            .andExpect(status().isForbidden());
    }
    @Test void pendingMemberCanReadOwnStateAndPublicCatalog() throws Exception {
        String token=pending();
        mvc.perform(get("/api/auth/me").header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.profileSetupStatus").value("REQUIRED"));
        mvc.perform(get("/api/mobile/auth/me").header("Authorization",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.profileSetupStatus").value("REQUIRED"));
        mvc.perform(get("/api/characters/fixed").header("Authorization",token)).andExpect(status().isOk());
    }
    @Test void mobileProfileRouteIsNotPublicDespiteAuthPrefix() throws Exception {
        mvc.perform(post("/api/mobile/auth/signup/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"nick\",\"characterId\":1}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }
    @Test void userMissingAfterTokenIssuanceCannotReadOwnState() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+jwt.generateAccessToken("gone")))
            .andExpect(status().isUnauthorized());
    }
    private String pending() {
        given(identities.queryUserIdentityById("pending")).willReturn(Optional.of(new UserIdentityView("pending",null,"가입대기_a",null,"REQUIRED")));
        given(access.queryUserAccess("pending")).willReturn(Optional.of(new UserAccessView("pending",false,UserAccessProfileStatus.REQUIRED)));
        return "Bearer "+jwt.generateAccessToken("pending");
    }
}
