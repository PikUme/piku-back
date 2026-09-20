package com.pikume.back.user.auth.application.dto;

public record SocialSignupAuthenticationCommand(String provider, String subject, String email, boolean emailVerified, boolean emailAuthoritative, String callerBinding, String targetUserId) {
}
