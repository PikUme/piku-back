package com.pikume.back.user.auth.domain;

import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import static com.pikume.back.user.auth.domain.exception.OAuthRequestException.Reason.*;

/** Immutable persisted OAuth protocol state. Sensitive reusable values are ciphertext. */
public record OAuthAuthorizationRequest(String id, String stateHash, String callerBindingHash,
        String encryptedNonce, String encryptedCodeVerifier, String deviceId, Purpose purpose,
        String targetUserId, Channel channel, String registration, Status status,
        Instant createdAt, Instant expiresAt) {
    public enum Purpose { LOGIN, LINK }
    public enum Channel { WEB, MOBILE }
    public enum Status { PENDING, PROCESSING, CONSUMED, FAILED }

    public void requireClaimable(String bindingHash, Channel expectedChannel, Instant now) {
        if (bindingHash == null || !MessageDigest.isEqual(callerBindingHash.getBytes(StandardCharsets.UTF_8),
                bindingHash.getBytes(StandardCharsets.UTF_8))) throw new OAuthRequestException(BINDING_MISMATCH);
        if (channel != expectedChannel) throw new OAuthRequestException(CHANNEL_MISMATCH);
        if (!now.isBefore(expiresAt)) throw new OAuthRequestException(EXPIRED);
        if (status != Status.PENDING) throw new OAuthRequestException(REPLAY);
    }
    public OAuthAuthorizationRequest withStatus(Status next) {
        return new OAuthAuthorizationRequest(id, stateHash, callerBindingHash, encryptedNonce,
                encryptedCodeVerifier, deviceId, purpose, targetUserId, channel, registration, next, createdAt, expiresAt);
    }
}
