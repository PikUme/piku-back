package com.pikume.back.user.auth.application.dto;

import java.time.LocalDateTime;

public record SignupVerificationSent(LocalDateTime expiresAt, LocalDateTime resendAvailableAt) {
}
