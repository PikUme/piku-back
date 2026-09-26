package com.pikume.back.user.auth.application.dto;

public record SignupProgress(SignupNextAction nextAction, String email, String userId, String profileSetupStatus, java.time.Instant expiresAt) {
}
