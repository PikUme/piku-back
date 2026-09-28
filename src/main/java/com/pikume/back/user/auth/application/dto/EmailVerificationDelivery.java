package com.pikume.back.user.auth.application.dto;
import java.time.Instant;
public record EmailVerificationDelivery(Instant expiresAt, Instant resendAvailableAt) {}
