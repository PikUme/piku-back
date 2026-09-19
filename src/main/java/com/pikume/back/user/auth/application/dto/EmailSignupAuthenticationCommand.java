package com.pikume.back.user.auth.application.dto;

public record EmailSignupAuthenticationCommand(String challengeId, String email, String code, String password, String callerBinding) {
}
