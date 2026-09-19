package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.port.out.AuthenticationTokenPort;
import com.pikume.back.user.auth.application.port.out.RefreshSessionPort;
import com.pikume.back.user.auth.application.port.out.RevokeDevicePushTokenPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("UserSessionService")
class UserSessionServiceTest {

	private final AuthenticationTokenPort tokens = mock(AuthenticationTokenPort.class);
	private final RefreshSessionPort sessions = mock(RefreshSessionPort.class);
	private final RevokeDevicePushTokenPort pushTokens = mock(RevokeDevicePushTokenPort.class);
	private final com.pikume.back.user.application.port.in.QueryUserAccessUseCase users = mock(com.pikume.back.user.application.port.in.QueryUserAccessUseCase.class);
    private final UserSessionService service = new UserSessionService(tokens, sessions, pushTokens, users);

	@Test
	@DisplayName("유효한 저장 갱신 세션으로 Access Token을 재발급한다")
	void reissuesAccessTokenForStoredSession() {
		given(tokens.isTokenValid("refresh")).willReturn(true);
		given(sessions.loadSessionByRefreshToken("refresh")).willReturn(Optional.of(
				new RefreshSessionPort.RefreshSession("user-1-device-1", "refresh", "user-1")));
		given(users.queryUserAccess("user-1")).willReturn(Optional.of(new com.pikume.back.user.application.dto.UserAccessView("user-1",false,com.pikume.back.user.application.dto.UserAccessProfileStatus.REQUIRED)));
        given(tokens.generateAccessToken("user-1")).willReturn("new-access");

		assertThat(service.reissueAccessToken("refresh")).isEqualTo("new-access");
	}

	@Test
	@DisplayName("유효하지 않은 토큰은 저장소에서 삭제한다")
	void deletesInvalidRefreshToken() {
		given(tokens.isTokenValid("invalid")).willReturn(false);

		assertThat(service.reissueAccessToken("invalid")).isNull();
		then(sessions).should().removeSessionByRefreshToken("invalid");
	}

	@Test
	@DisplayName("모바일 로그아웃은 갱신 세션의 기기가 일치할 때만 푸시 토큰을 해제한다")
	void revokesPushTokenOnlyForMatchingDevice() {
		given(sessions.loadSessionByRefreshToken("refresh")).willReturn(Optional.of(
				new RefreshSessionPort.RefreshSession("user-1-device-a", "refresh", "user-1")));

		service.logoutWithRefreshToken("refresh", "device-b");

		then(pushTokens).should(never()).revokeDevicePushToken("user-1", "device-b");
		then(sessions).should().removeSessionByRefreshToken("refresh");
	}
    @Test void refusesRefreshAfterWithdrawal() {
        given(tokens.isTokenValid("refresh")).willReturn(true);
        given(sessions.loadSessionByRefreshToken("refresh")).willReturn(Optional.of(new RefreshSessionPort.RefreshSession("user-device", "refresh", "user")));
        given(users.queryUserAccess("user")).willReturn(Optional.of(new com.pikume.back.user.application.dto.UserAccessView("user",true,com.pikume.back.user.application.dto.UserAccessProfileStatus.COMPLETED)));
        assertThat(service.reissueAccessToken("refresh")).isNull();
        then(sessions).should().removeSessionByRefreshToken("refresh");
        then(tokens).should(never()).generateAccessToken("user");
    }

}
