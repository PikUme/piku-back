package com.pikume.back.user.auth.adapter.in.web;

public class SignupWebException extends RuntimeException {
    public enum Reason { ORIGIN_FORBIDDEN, CSRF_INVALID, CALLER_REQUIRED, PROOF_REQUIRED, DEVICE_REQUIRED, CONFIGURATION }
    private final Reason reason;
    public SignupWebException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason getReason() { return reason; }
}
