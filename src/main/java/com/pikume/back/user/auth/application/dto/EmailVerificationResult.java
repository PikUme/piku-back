package com.pikume.back.user.auth.application.dto;

import java.time.Instant;

public record EmailVerificationResult(String emailVerificationToken, Instant expiresAt) {}
