package com.pikume.back.user.auth.application.dto;

public record SignUpCommand(String email, String password, String nickname, Long fixedCharacterId, String emailVerificationToken) {
}
