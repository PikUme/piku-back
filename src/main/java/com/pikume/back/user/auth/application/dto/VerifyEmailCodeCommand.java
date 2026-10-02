package com.pikume.back.user.auth.application.dto;

public record VerifyEmailCodeCommand(String email, String code) {}
