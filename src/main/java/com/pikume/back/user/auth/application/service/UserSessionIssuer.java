package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.dto.UserAccessView;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import com.pikume.back.user.auth.application.dto.LoginResult;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.port.in.IssueUserSessionUseCase;
import com.pikume.back.user.auth.application.port.out.AuthenticationTokenPort;
import com.pikume.back.user.auth.application.port.out.RefreshSessionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSessionIssuer implements IssueUserSessionUseCase {
    private final QueryUserAccessUseCase access;
    private final QueryUserIdentityUseCase identities;
    private final AuthenticationTokenPort tokens;
    private final RefreshSessionPort sessions;

    @Override
    @Transactional
    public LoginResult issueSession(String userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128) throw new InvalidCredentialsException();
        UserAccessView user = requireActive(userId);
        var identity = identities.queryUserIdentityById(userId).orElseThrow(InvalidCredentialsException::new);
        String accessToken = tokens.generateAccessToken(userId);
        String refreshToken = tokens.generateRefreshToken();
        sessions.storeSession(new RefreshSessionPort.RefreshSession(userId + "-" + deviceId, refreshToken, userId));
        return new LoginResult(accessToken, refreshToken, new LoginResult.UserInfo(userId,
            identity.nickname(), identity.avatarReference(), user.profileSetupStatus().name(), identity.characterId()));
    }



    private UserAccessView requireActive(String userId) {
        return access.queryUserAccess(userId).filter(user -> !user.withdrawn())
            .orElseThrow(InvalidCredentialsException::new);
    }
}
