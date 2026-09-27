package com.pikume.back.user.auth.application.exception;

public class GoogleAuthenticationException extends RuntimeException {
    public enum Reason { DISABLED, INVALID_IDENTITY, UNAVAILABLE, CONFIGURATION }
    private final Reason reason;

    public GoogleAuthenticationException(Reason reason) {
        super("Google authentication could not be completed");
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
