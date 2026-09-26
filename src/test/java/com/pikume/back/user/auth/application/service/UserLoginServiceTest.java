package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.dto.UserIdentityView;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import com.pikume.back.user.auth.application.dto.LoginCommand;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.port.out.AuthenticationTokenPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.RefreshSessionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@DisplayName("UserLoginService")
class UserLoginServiceTest {

	private final QueryUserIdentityUseCase users = mock(QueryUserIdentityUseCase.class);
	private final PasswordProtectionPort passwords = mock(PasswordProtectionPort.class);
	private final AuthenticationTokenPort tokens = mock(AuthenticationTokenPort.class);
	private final RefreshSessionPort sessions = mock(RefreshSessionPort.class);
	private final com.pikume.back.user.application.port.in.QueryUserAccessUseCase access = mock(com.pikume.back.user.application.port.in.QueryUserAccessUseCase.class);
    private final UserLoginService service = new UserLoginService(users, passwords,
        new UserSessionIssuer(access, users, tokens, sessions, passwords));

	@Test
	@DisplayName("계정 확인부터 토큰과 갱신 세션 저장까지 로그인 순서를 조정한다")
	void logsInUserAndStoresRefreshSession() {
		given(users.queryUserIdentityByEmail("user@example.com")).willReturn(Optional.of(
				new UserIdentityView(
						"user-1", "protected", "pikume",
						new UserAvatarReference("avatar", false, false))));
		given(passwords.matches("raw", "protected")).willReturn(true);
		given(access.queryUserAccess("user-1")).willReturn(Optional.of(new com.pikume.back.user.application.dto.UserAccessView("user-1",false,com.pikume.back.user.application.dto.UserAccessProfileStatus.COMPLETED)));
        var identity = users.queryUserIdentityByEmail("user@example.com");
        given(users.queryUserIdentityById("user-1")).willReturn(identity);
        given(tokens.generateAccessToken("user-1")).willReturn("access");
		given(tokens.generateRefreshToken()).willReturn("refresh");

		var result = service.login(new LoginCommand("user@example.com", "raw", "device-1"));

		assertThat(result.accessToken()).isEqualTo("access");
		assertThat(result.userInfo().nickname()).isEqualTo("pikume");
		then(sessions).should().storeSession(new RefreshSessionPort.RefreshSession(
				"user-1-device-1", "refresh", "user-1"));
	}

	@Test
	@DisplayName("비밀번호가 다르면 자격 증명 오류를 반환하고 토큰을 발급하지 않는다")
	void rejectsInvalidPassword() {
		given(users.queryUserIdentityByEmail("user@example.com")).willReturn(Optional.of(
				new UserIdentityView(
						"user-1", "protected", "pikume",
						new UserAvatarReference("avatar", false, false))));
		given(passwords.matches("wrong", "protected")).willReturn(false);

		assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "wrong", "device-1")))
				.isInstanceOf(InvalidCredentialsException.class);
		then(tokens).shouldHaveNoInteractions();
		then(sessions).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("잘못된 이메일 형식은 자격 증명 오류로 변환한다")
	void rejectsMalformedEmailAsInvalidCredentials() {
		assertThatThrownBy(() -> service.login(new LoginCommand("not-an-email", "raw", "device-1")))
				.isInstanceOf(InvalidCredentialsException.class);
		then(users).shouldHaveNoInteractions();
		then(tokens).shouldHaveNoInteractions();
		then(sessions).shouldHaveNoInteractions();
	}
}
