package com.pikume.back.user.auth.adapter.out.google;

import com.pikume.back.user.auth.adapter.out.config.OAuthProtocolProperties;
import com.pikume.back.user.auth.application.port.out.OAuthProtocolPolicyPort;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.Channel;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.springframework.stereotype.Component;

@Component
public class GoogleOAuthProtocolPolicy implements OAuthProtocolPolicyPort {
    private final GoogleOAuthProperties google;
    private final OAuthProtocolProperties protocol;
    public GoogleOAuthProtocolPolicy(GoogleOAuthProperties google, OAuthProtocolProperties protocol) {
        this.google = google; this.protocol = protocol;
    }
    @Override public void requireEnabled() {
        if (!google.isEnabled()) throw new OAuthRequestException(OAuthRequestException.Reason.DISABLED);
    }
    @Override public void requireRegistration(Channel channel, String registration) {
        requireEnabled();
        google.validate();
        if ((channel == Channel.WEB && !"web".equals(registration)) ||
                (channel == Channel.MOBILE && (registration == null || "web".equals(registration)
                        || !google.getMobileRegistrations().containsKey(registration))))
            throw new OAuthRequestException(OAuthRequestException.Reason.INVALID_REQUEST);
    }
    public int cleanupBatchSize() { return protocol.getCleanupBatchSize(); }
    public int callerHourlyLimit() { return protocol.getCallerHourlyLimit(); }
    public int originHourlyLimit() { return protocol.getOriginHourlyLimit(); }
}
