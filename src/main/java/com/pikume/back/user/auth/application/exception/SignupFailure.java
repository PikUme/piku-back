package com.pikume.back.user.auth.application.exception;

public enum SignupFailure {
    INVALID_REQUEST, INVALID_EMAIL,
    CHALLENGE_INVALID, CODE_EXPIRED, CODE_MISMATCH, ATTEMPTS_EXHAUSTED, RATE_LIMITED, EMAIL_SEND_FAILED
}
