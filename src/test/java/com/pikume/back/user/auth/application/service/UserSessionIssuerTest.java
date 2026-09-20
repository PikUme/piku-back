package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.dto.*;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.port.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class UserSessionIssuerTest {
    @Mock QueryUserAccessUseCase access;
    @Mock QueryUserIdentityUseCase identities;
    @Mock AuthenticationTokenPort tokens;
    @Mock RefreshSessionPort sessions;
    @Mock PasswordProtectionPort passwords;
    @InjectMocks UserSessionIssuer service;

    @Test void pendingMemberCanResumeWithNormalSession() {
        given(access.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user", false, UserAccessProfileStatus.REQUIRED)));
        given(identities.queryUserIdentityById("user")).willReturn(Optional.of(new UserIdentityView("user", null,"가입대기_ab",new UserAvatarReference("base.webp",false,true))));
        given(tokens.generateAccessToken("user")).willReturn("access");
        given(tokens.generateRefreshToken()).willReturn("refresh");
        var result = service.issueSession("user", "device");
        assertThat(result.userInfo().profileSetupStatus()).isEqualTo("REQUIRED");
        assertThat(result.accessToken()).isEqualTo("access");
        then(sessions).should().storeSession(new RefreshSessionPort.RefreshSession("user-device","refresh","user"));
    }
    @Test void missingMemberCannotReceiveTokens() {
        assertThatThrownBy(() -> service.issueSession("gone", "device")).isInstanceOf(InvalidCredentialsException.class);
        then(tokens).shouldHaveNoInteractions();
    }
    @Test void withdrawnMemberCannotReceiveTokens() {
        given(access.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user",true,UserAccessProfileStatus.COMPLETED)));
        assertThatThrownBy(() -> service.issueSession("user", "device")).isInstanceOf(InvalidCredentialsException.class);
        then(tokens).shouldHaveNoInteractions();
    }
    @Test void blankDeviceCannotReceiveTokens() {
        assertThatThrownBy(() -> service.issueSession("user", " ")).isInstanceOf(InvalidCredentialsException.class);
        then(tokens).shouldHaveNoInteractions();
        then(access).shouldHaveNoInteractions();
    }
    @Test void socialOnlyUserCannotUsePasswordReauthentication() {
        given(access.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user",false,UserAccessProfileStatus.COMPLETED)));
        given(identities.queryUserIdentityById("user")).willReturn(Optional.of(new UserIdentityView("user",null,"nick",null)));
        assertThatThrownBy(() -> service.reauthenticate("user", "password")).isInstanceOf(InvalidCredentialsException.class);
        then(passwords).shouldHaveNoInteractions();
        then(tokens).shouldHaveNoInteractions();
    }
    @Test void successfulReauthenticationDoesNotIssueSession() {
        given(access.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user",false,UserAccessProfileStatus.COMPLETED)));
        given(identities.queryUserIdentityById("user")).willReturn(Optional.of(new UserIdentityView("user","hash","nick",null)));
        given(passwords.matches("password","hash")).willReturn(true);
        service.reauthenticate("user", "password");
        then(tokens).shouldHaveNoInteractions();
    }
}
