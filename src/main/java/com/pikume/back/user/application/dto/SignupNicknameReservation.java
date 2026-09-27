package com.pikume.back.user.application.dto;

import java.time.Instant;

public record SignupNicknameReservation(String nickname, Instant expiresAt) { }
