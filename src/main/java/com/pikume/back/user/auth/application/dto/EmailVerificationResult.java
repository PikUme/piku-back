package com.pikume.back.user.auth.application.dto;

import java.time.LocalDateTime;

public record EmailVerificationResult(String emailVerificationToken, LocalDateTime expiresAt) {}
