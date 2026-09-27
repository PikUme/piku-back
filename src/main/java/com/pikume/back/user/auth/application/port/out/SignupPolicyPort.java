package com.pikume.back.user.auth.application.port.out;

public interface SignupPolicyPort {
    int maxCodeAttempts();
    int resendSeconds();
    int emailHourlyLimit();
    int originHourlyLimit();
}
