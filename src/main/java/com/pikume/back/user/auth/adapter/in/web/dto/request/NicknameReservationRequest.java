package com.pikume.back.user.auth.adapter.in.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record NicknameReservationRequest(@NotBlank String emailVerificationToken, String nickname) {}
