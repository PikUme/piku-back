package com.pikume.back.user.auth.application.dto;

import java.time.LocalDateTime;

public record EmailVerificationDelivery(LocalDateTime expiresAt, LocalDateTime resendAvailableAt) {}
