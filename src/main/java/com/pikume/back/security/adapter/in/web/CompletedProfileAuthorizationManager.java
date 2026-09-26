package com.pikume.back.security.adapter.in.web;

import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.user.application.dto.UserAccessProfileStatus;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CompletedProfileAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final QueryUserAccessUseCase users;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return new AuthorizationDecision(false);
        }
        var user = users.queryUserAccess(principal.getId()).orElse(null);
        if (user == null || user.withdrawn()) return new AuthorizationDecision(false);
        if (user.profileSetupStatus() == UserAccessProfileStatus.REQUIRED) throw new ProfileSetupRequiredException();
        return new AuthorizationDecision(true);
    }
}
