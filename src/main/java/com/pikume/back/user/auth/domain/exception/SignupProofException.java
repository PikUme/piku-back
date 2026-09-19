package com.pikume.back.user.auth.domain.exception;

public class SignupProofException extends RuntimeException {
    public enum Reason { INVALID, EXPIRED, ALREADY_USED, EMAIL_REQUIRED, FLOW_MISMATCH }

    private final Reason reason;

    public SignupProofException(Reason reason) {
        super("가입 인증 증명을 사용할 수 없습니다.");
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
