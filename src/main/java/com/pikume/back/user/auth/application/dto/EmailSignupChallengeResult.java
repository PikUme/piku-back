package com.pikume.back.user.auth.application.dto;

public record EmailSignupChallengeResult(String challengeId, java.time.Instant expiresAt, java.time.Instant resendAvailableAt) {
}
