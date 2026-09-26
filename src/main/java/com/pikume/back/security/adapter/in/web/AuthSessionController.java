package com.pikume.back.security.adapter.in.web;

import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.security.adapter.in.web.problem.SecurityProblemType;
import com.pikume.back.security.adapter.in.web.dto.response.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth Session", description = "현재 인증 세션 확인 API")
@RestController
@RequestMapping({"/api/auth", "/api/mobile/auth"})
@RequiredArgsConstructor
public class AuthSessionController {

	private final ProblemDetailFactory problemDetailFactory;
	private final AuthUserResponseMapper authUserResponseMapper;

	@Operation(summary = "현재 인증 사용자 조회", description = "Access Token을 검증하고 현재 인증된 사용자 정보를 반환합니다.")
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "토큰 검증 성공",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = LoginResponse.class))),
			@ApiResponse(
					responseCode = "401",
					description = "인증 필요",
					content = @Content(
							mediaType = "application/problem+json",
							schema = @Schema(implementation = ProblemDetail.class)))
	})
	@GetMapping("/me")
	public ResponseEntity<?> getCurrentUser(
			@AuthenticationPrincipal UserPrincipal userDetails,
			HttpServletRequest request) {
		if (userDetails == null || userDetails.getId() == null) {
			ProblemDetail problemDetail = problemDetailFactory.create(
					SecurityProblemType.UNAUTHENTICATED,
					"인증이 필요합니다.",
					request.getRequestURI());
			return ResponseEntity.status(SecurityProblemType.UNAUTHENTICATED.status()).body(problemDetail);
		}

		return ResponseEntity.ok(new LoginResponse(
				"토큰 검증 성공",
				authUserResponseMapper.toDisplayUserInfo(userDetails)));
	}
}
