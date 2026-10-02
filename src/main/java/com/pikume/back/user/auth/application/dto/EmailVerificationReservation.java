package com.pikume.back.user.auth.application.dto;

import java.time.LocalDateTime;

public record EmailVerificationReservation(String generation, LocalDateTime expiresAt,
		LocalDateTime resendAvailableAt) {}
