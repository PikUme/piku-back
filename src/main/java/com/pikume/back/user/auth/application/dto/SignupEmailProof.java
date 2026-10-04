package com.pikume.back.user.auth.application.dto;

import java.time.LocalDateTime;

public record SignupEmailProof(String version, String tokenHash, LocalDateTime expiresAt) {}
