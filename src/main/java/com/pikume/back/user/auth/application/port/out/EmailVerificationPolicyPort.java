package com.pikume.back.user.auth.application.port.out;

public interface EmailVerificationPolicyPort {
    int maxCodeAttempts();
    int resendSeconds();
    int emailHourlyLimit();
}
