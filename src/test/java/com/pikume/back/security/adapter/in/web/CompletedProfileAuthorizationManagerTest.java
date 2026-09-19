package com.pikume.back.security.adapter.in.web;

import com.pikume.back.user.application.dto.*;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import com.pikume.back.security.principal.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;

class CompletedProfileAuthorizationManagerTest {
    QueryUserAccessUseCase users = mock(QueryUserAccessUseCase.class);
    CompletedProfileAuthorizationManager manager = new CompletedProfileAuthorizationManager(users);
    @Test void requiredUserCannotPerformMemberAction() {
        given(users.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user",false,UserAccessProfileStatus.REQUIRED)));
        assertThatThrownBy(() -> check()).isInstanceOf(ProfileSetupRequiredException.class);
    }
    @Test void completionIsReadOnEveryRequest() {
        given(users.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user",false,UserAccessProfileStatus.COMPLETED)))
            .willReturn(Optional.of(new UserAccessView("user",false,UserAccessProfileStatus.REQUIRED)));
        assertThat(check()).isTrue();
        assertThatThrownBy(() -> check()).isInstanceOf(ProfileSetupRequiredException.class);
    }
    @Test void withdrawnUserIsRejected() {
        given(users.queryUserAccess("user")).willReturn(Optional.of(new UserAccessView("user",true,UserAccessProfileStatus.COMPLETED)));
        assertThat(check()).isFalse();
    }
    @Test void unknownUserIsRejected() { assertThat(check()).isFalse(); }
    private boolean check() {
        var principal = UserPrincipal.withAvatarReference("user","nick",null);
        var auth = new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());
        return manager.check(() -> auth,new RequestAuthorizationContext(new MockHttpServletRequest("POST","/api/diary"))).isGranted();
    }
}
