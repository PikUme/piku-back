package com.pikume.back.user.auth.application.dto;
import java.time.Instant;
public record GoogleNativeChallenge(String state, String nonce, Instant expiresAt) {}
