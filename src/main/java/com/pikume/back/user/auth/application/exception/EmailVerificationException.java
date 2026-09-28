package com.pikume.back.user.auth.application.exception;

public class EmailVerificationException extends RuntimeException {
    private final EmailVerificationFailure reason;
    public EmailVerificationException(EmailVerificationFailure reason) { super(reason.name()); this.reason = reason; }
    public EmailVerificationException(EmailVerificationFailure reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
    public EmailVerificationFailure getReason() { return reason; }
}
