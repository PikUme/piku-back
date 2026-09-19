package com.pikume.back.user.auth.application.dto;

public record EmailSignupChallengeCommand(String email, String callerBinding, String requestOriginKey, String challengeId, String signupProof) {
}
