package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.security.adapter.in.web.AuthUserResponseMapper;
import com.pikume.back.security.adapter.in.web.dto.response.MobileTokenBundle;
import com.pikume.back.security.adapter.in.web.dto.response.UserInfo;
import com.pikume.back.security.config.UserTokenSettings;
import com.pikume.back.user.auth.application.dto.SignupProgress;
import com.pikume.back.user.auth.application.dto.SignupProofResult;
import com.pikume.back.user.auth.application.port.in.IssueUserSessionUseCase;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonInclude;

@Component
@RequiredArgsConstructor
public class SignupSessionResponseWriter {
    private final IssueUserSessionUseCase sessions;
    private final AuthUserResponseMapper mapper;
    private final SignupWebCredentials credentials;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepResponse(SignupProgress progress, UserInfo user, MobileTokenBundle tokens, String proof) {}

    public StepResponse write(SignupProofResult result, String deviceId, boolean mobile, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!mobile && result.proof() != null) credentials.storeProof(response, result.proof(), result.progress().expiresAt());
        if (result.progress().userId() == null) {
            if (!mobile) clearRefresh(response);
            return new StepResponse(result.progress(), null, null, mobile ? result.proof() : null);
        }
        var session = sessions.issueSession(result.progress().userId(), deviceId);
        UserInfo user = mapper.toDisplayUserInfo(session.userInfo());
        if (mobile) return new StepResponse(result.progress(), user,
            new MobileTokenBundle("Bearer", session.accessToken(), session.refreshToken(),
                UserTokenSettings.ACCESS_TOKEN_EXPIRATION_MILLIS / 1000L, UserTokenSettings.REFRESH_TOKEN_EXPIRATION_MILLIS / 1000L), result.proof());
        if (result.proof() == null) credentials.clearProof(response);
        response.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken(), UserTokenSettings.REFRESH_TOKEN_EXPIRATION_MILLIS / 1000L));
        return new StepResponse(result.progress(), user, null, null);
    }

    public void clearRefresh(HttpServletResponse response) { response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("",0)); }
    private String refreshCookie(String token, long age) {
        return ResponseCookie.from(com.pikume.back.security.adapter.in.web.AuthWebConstants.REFRESH_TOKEN_COOKIE,token)
            .httpOnly(true).secure(true).path("/").sameSite("Lax").maxAge(age).build().toString();
    }
}
