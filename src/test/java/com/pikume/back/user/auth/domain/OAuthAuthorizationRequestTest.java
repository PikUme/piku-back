package com.pikume.back.user.auth.domain;

import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class OAuthAuthorizationRequestTest {
    final Instant now = Instant.parse("2026-09-07T00:00:00Z");
    OAuthAuthorizationRequest pending() {
        return new OAuthAuthorizationRequest("id", "state-hash", "caller-hash", "encrypted-nonce", "encrypted-verifier",
            "device", OAuthAuthorizationRequest.Purpose.LINK, "user-123", OAuthAuthorizationRequest.Channel.WEB,
            "web", OAuthAuthorizationRequest.Status.PENDING, now, now.plusSeconds(600));
    }
    @Test void wrongCallerCannotClaimOrInvalidateRequest() {
        assertThatThrownBy(() -> pending().requireClaimable("other", OAuthAuthorizationRequest.Channel.WEB, now))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.BINDING_MISMATCH);
        assertThatCode(() -> pending().requireClaimable("caller-hash", OAuthAuthorizationRequest.Channel.WEB, now)).doesNotThrowAnyException();
    }
    @Test void wrongChannelCannotClaim() {
        assertThatThrownBy(() -> pending().requireClaimable("caller-hash", OAuthAuthorizationRequest.Channel.MOBILE, now))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.CHANNEL_MISMATCH);
    }
    @Test void exactExpiryBoundaryRejects() {
        assertThatThrownBy(() -> pending().requireClaimable("caller-hash", OAuthAuthorizationRequest.Channel.WEB, now.plusSeconds(600)))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.EXPIRED);
    }
    @Test void everyNonPendingStateRejectsReplay() {
        for (var status : new OAuthAuthorizationRequest.Status[]{OAuthAuthorizationRequest.Status.PROCESSING, OAuthAuthorizationRequest.Status.CONSUMED, OAuthAuthorizationRequest.Status.FAILED}) {
            assertThatThrownBy(() -> pending().withStatus(status).requireClaimable("caller-hash", OAuthAuthorizationRequest.Channel.WEB, now))
                .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.REPLAY);
        }
    }
}
