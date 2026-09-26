package com.pikume.back.user.auth.application.port.out;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.Channel;
public interface OAuthProtocolPolicyPort {
    void requireEnabled();
    void requireRegistration(Channel channel, String registration);
    int cleanupBatchSize();
    int callerHourlyLimit();
    int originHourlyLimit();
}
