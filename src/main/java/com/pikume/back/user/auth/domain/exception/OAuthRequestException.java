package com.pikume.back.user.auth.domain.exception;

/** Stable public reason only; no external OAuth payload or secret is retained. */
public class OAuthRequestException extends RuntimeException {
    public enum Reason { DISABLED, INVALID_REQUEST, NOT_FOUND, BINDING_MISMATCH, CHANNEL_MISMATCH, EXPIRED, REPLAY, RATE_LIMITED, CONFIGURATION }
    private final Reason reason;
    public OAuthRequestException(Reason reason) { super("OAuth request rejected: " + reason.name()); this.reason = reason; }
    public Reason getReason() { return reason; }
}
