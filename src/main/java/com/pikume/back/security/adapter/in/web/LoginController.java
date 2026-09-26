package com.pikume.back.security.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.pikume.back.security.config.UserTokenSettings;
import com.pikume.back.global.dto.MessageResponse;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.user.auth.application.dto.LoginCommand;
import com.pikume.back.user.auth.application.dto.LoginResult;
import com.pikume.back.user.auth.application.dto.ReissueSessionResult;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.security.adapter.in.web.dto.request.LoginRequest;
import com.pikume.back.security.adapter.in.web.dto.response.LoginResponse;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.util.CookieUtils;
import com.pikume.back.security.adapter.in.web.problem.SecurityProblemType;
import com.pikume.back.user.auth.application.port.in.LoginUseCase;
import com.pikume.back.user.auth.application.port.in.LogoutUseCase;
import com.pikume.back.user.auth.application.port.in.ReissueSessionUseCase;

@Tag(name = "Login", description = "로그인/로그아웃/토큰 재발급 API")
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class LoginController {

	private final LoginUseCase loginUseCase;
	private final ReissueSessionUseCase reissueSessionUseCase;
	private final LogoutUseCase logoutUseCase;
	private final CookieUtils cookieUtils;
	private final ProblemDetailFactory problemDetailFactory;
	private final AuthUserResponseMapper authUserResponseMapper;

	@Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인을 진행하고 Access/Refresh 토큰을 발급합니다.")
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "로그인 성공",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = LoginResponse.class))),
			@ApiResponse(
					responseCode = "401",
					description = "로그인 실패",
					content = @Content(
							mediaType = "application/problem+json",
							schema = @Schema(implementation = ProblemDetail.class)))
	})
	@PostMapping("/login")
	public ResponseEntity<?> login(@RequestBody LoginRequest dto, HttpServletRequest request) {
		String deviceId = request.getHeader(AuthWebConstants.DEVICE_ID_HEADER);
		log.info("event=login_request_received outcome=accepted");

		try {
			LoginResult loginResult = loginUseCase.login(new LoginCommand(dto.getEmail(), dto.getPassword(), deviceId));
			log.info("event=login_response_ready outcome=success userId={}", loginResult.userInfo().id());

			ResponseCookie responseCookie = newRefreshCookie(loginResult.refreshToken());

			LoginResponse loginResponse = new LoginResponse(
					"로그인 성공",
					authUserResponseMapper.toDisplayUserInfo(loginResult.userInfo()));

			return ResponseEntity.ok()
					.header(HttpHeaders.AUTHORIZATION,
							AuthWebConstants.BEARER_PREFIX + loginResult.accessToken())
					.header(HttpHeaders.SET_COOKIE, responseCookie.toString(), SignupProofCookie.expired().toString())
					.body(loginResponse);
		} catch (InvalidCredentialsException e) {
			log.warn("event=login_failed outcome=denied reason=invalid_credentials");
			return buildProblem(SecurityProblemType.INVALID_CREDENTIALS, e.getMessage(), request);
		}
	}

	@Operation(summary = "Access Token 재발급", description = "Cookie에 담긴 Refresh Token을 사용하여 새로운 Access Token을 재발급합니다.")
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "토큰 재발급 성공",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = MessageResponse.class))),
			@ApiResponse(
					responseCode = "401",
					description = "Refresh Token 만료",
					content = @Content(
							mediaType = "application/problem+json",
							schema = @Schema(implementation = ProblemDetail.class)))
	})
	@PostMapping("/reissue")
	public ResponseEntity<?> reissue(HttpServletRequest request) {
		String refreshToken = cookieUtils.getCookieValue(request, AuthWebConstants.REFRESH_TOKEN_COOKIE);

		ReissueSessionResult reissueResult = reissueSessionUseCase.reissueSession(refreshToken);
		if (reissueResult == null) {
			ResponseCookie resetCookie = deleteRefreshCookie();
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.header(HttpHeaders.SET_COOKIE, resetCookie.toString())
					.body(problemDetailFactory.create(
							SecurityProblemType.INVALID_REFRESH_TOKEN,
							"유효하지 않은 Refresh Token입니다.",
							request.getRequestURI()));
		}
		return ResponseEntity.ok()
				.header(HttpHeaders.AUTHORIZATION, AuthWebConstants.BEARER_PREFIX + reissueResult.accessToken())
				.body(new MessageResponse("토큰 재발급 성공"));
	}

	@Operation(summary = "로그아웃", description = "사용자 로그아웃을 처리하고 Refresh Token을 삭제합니다.")
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "로그아웃 성공",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = MessageResponse.class))),
			@ApiResponse(
					responseCode = "401",
					description = "로그인 상태가 아님",
					content = @Content(
							mediaType = "application/problem+json",
							schema = @Schema(implementation = ProblemDetail.class)))
	})
	@PostMapping("/logout")
	public ResponseEntity<?> logout(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
		if (user == null || user.getId() == null) {
			return buildProblem(SecurityProblemType.UNAUTHENTICATED, "로그인 상태가 아닙니다.", request);
		}
		String deviceId = request.getHeader(AuthWebConstants.DEVICE_ID_HEADER);
		logoutUseCase.logout(user.getId(), deviceId);

		ResponseCookie deleteCookie = deleteRefreshCookie();

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, deleteCookie.toString(), SignupProofCookie.expired().toString())
				.body(new MessageResponse("로그아웃 완료"));
	}

	private ResponseEntity<ProblemDetail> buildProblem(SecurityProblemType problemType, String detail,
			HttpServletRequest request) {
		ProblemDetail problemDetail = problemDetailFactory.create(problemType, detail, request.getRequestURI());
		return ResponseEntity.status(problemType.status()).body(problemDetail);
	}

	private ResponseCookie newRefreshCookie(String refreshToken) {
		return ResponseCookie.from(AuthWebConstants.REFRESH_TOKEN_COOKIE, refreshToken)
				.httpOnly(true).secure(true).path("/")
				.maxAge(UserTokenSettings.REFRESH_TOKEN_EXPIRATION_MILLIS / 1000L).sameSite("Lax")
				.build();
	}

	private ResponseCookie deleteRefreshCookie() {
		return ResponseCookie.from(AuthWebConstants.REFRESH_TOKEN_COOKIE, "")
				.httpOnly(true).secure(true).path("/").maxAge(0).sameSite("Lax")
				.build();
	}
}
