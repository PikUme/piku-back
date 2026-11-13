package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.user.adapter.in.web.dto.request.SignupNicknameReservationRequest;
import com.pikume.back.user.adapter.in.web.dto.response.SignupNicknameReservationResponse;
import com.pikume.back.user.application.port.in.ReserveSignupNicknameUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/signup/nickname-reservations")
@RequiredArgsConstructor
public class SignupNicknameReservationController {

	private final ReserveSignupNicknameUseCase reserveSignupNicknameUseCase;

	@PostMapping
	public ResponseEntity<SignupNicknameReservationResponse> reserve(
			@Valid @RequestBody SignupNicknameReservationRequest request) {
		var result = reserveSignupNicknameUseCase.reserve(
				request.email(), request.emailVerificationToken(), request.nickname());
		return ResponseEntity.ok(new SignupNicknameReservationResponse(result.nickname(), result.expiresAt()));
	}
}
