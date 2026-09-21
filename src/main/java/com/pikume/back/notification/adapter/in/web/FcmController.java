package com.pikume.back.notification.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.pikume.back.notification.adapter.in.web.dto.FcmTokenRequest;
import com.pikume.back.notification.application.port.in.RegisterPushTokenUseCase;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fcm")
@Tag(name = "Fcm", description = "Fcm 관리 API")
public class FcmController {

	private final RegisterPushTokenUseCase registerPushTokenUseCase;

	@Operation(summary = "FCM 토큰 저장", description = "FCM 토큰과 디바이스 ID를 저장합니다.")
	@PostMapping
	public ResponseEntity<Void> saveToken(@RequestBody FcmTokenRequest request) {
		registerPushTokenUseCase.registerPushToken(
				request.getUserId(),
				request.getToken(),
				request.getDeviceId());
		return ResponseEntity.ok().build();
	}
}
