package com.pikume.back.user.auth.application.dto;

public record SocialSignupEmailCommand(String proof, String challengeId, String email, String code, String callerBinding) {
}
