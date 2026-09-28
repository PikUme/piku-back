package com.pikume.back.user.auth.application.dto;

import java.time.Instant;

public record NicknameReservationResult(String nickname, Instant expiresAt) {}
