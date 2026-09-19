package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.ReissueSessionResult;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import com.pikume.back.user.auth.application.port.in.LogoutUseCase;
import com.pikume.back.user.auth.application.port.in.ReissueSessionUseCase;
import com.pikume.back.user.auth.application.port.out.AuthenticationTokenPort;
import com.pikume.back.user.auth.application.port.out.RefreshSessionPort;
import com.pikume.back.user.auth.application.port.out.RevokeDevicePushTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserSessionService implements ReissueSessionUseCase, LogoutUseCase {

	private final AuthenticationTokenPort authenticationTokenPort;
	private final RefreshSessionPort refreshSessionPort;
	private final RevokeDevicePushTokenPort revokeDevicePushTokenPort;
	private final QueryUserAccessUseCase queryUserAccessUseCase;

	@Override
	@Transactional
	public String reissueAccessToken(String refreshToken) {
		RefreshSessionPort.RefreshSession session = validSession(refreshToken);
		return session == null ? null : authenticationTokenPort.generateAccessToken(session.userId());
	}

	@Override
	@Transactional
	public ReissueSessionResult reissueSession(String refreshToken) {
		String accessToken = reissueAccessToken(refreshToken);
		return accessToken == null ? null : new ReissueSessionResult(
				accessToken, refreshToken,
				authenticationTokenPort.accessTokenExpiresInSeconds(),
				authenticationTokenPort.refreshTokenExpiresInSeconds());
	}

	@Override
	@Transactional
	public void logout(String userId, String deviceId) {
		if (StringUtils.hasText(userId) && StringUtils.hasText(deviceId)) {
			revokeDevicePushTokenPort.revokeDevicePushToken(userId, deviceId);
		}
		refreshSessionPort.removeSession(userId + "-" + deviceId);
	}

	@Override
	@Transactional
	public void logoutWithRefreshToken(String refreshToken, String deviceId) {
		if (!StringUtils.hasText(refreshToken)) return;
		RefreshSessionPort.RefreshSession session = refreshSessionPort.loadSessionByRefreshToken(refreshToken).orElse(null);
		if (session != null && StringUtils.hasText(deviceId)
				&& session.key().equals(session.userId() + "-" + deviceId)) {
			revokeDevicePushTokenPort.revokeDevicePushToken(session.userId(), deviceId);
		}
		refreshSessionPort.removeSessionByRefreshToken(refreshToken);
	}

	private RefreshSessionPort.RefreshSession validSession(String refreshToken) {
		if (!StringUtils.hasText(refreshToken)) return null;
		if (!authenticationTokenPort.isTokenValid(refreshToken)) {
			refreshSessionPort.removeSessionByRefreshToken(refreshToken);
			return null;
		}
		RefreshSessionPort.RefreshSession session = refreshSessionPort.loadSessionByRefreshToken(refreshToken).orElse(null);
		if (session == null || !StringUtils.hasText(session.userId())) {
			if (session != null) refreshSessionPort.removeSessionByRefreshToken(refreshToken);
			return null;
		}
		if (queryUserAccessUseCase.queryUserAccess(session.userId()).filter(user -> !user.withdrawn()).isEmpty()) {
			refreshSessionPort.removeSessionByRefreshToken(refreshToken);
			return null;
		}
		return session;
	}
}
