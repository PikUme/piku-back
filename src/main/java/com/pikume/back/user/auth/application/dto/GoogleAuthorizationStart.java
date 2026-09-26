package com.pikume.back.user.auth.application.dto;
import java.time.Instant;
public record GoogleAuthorizationStart(String authorizationUrl, Instant expiresAt) {}
