package com.pikume.back.user.auth.application.exception;

public class SignupFlowException extends RuntimeException {
    private final SignupFailure reason;
    public SignupFlowException(SignupFailure reason) { super(reason.name()); this.reason = reason; }
    public SignupFlowException(SignupFailure reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
    public SignupFailure getReason() { return reason; }
}
