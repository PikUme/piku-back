package com.pikume.back.user.auth.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pikume.back.global.dto.MessageResponse;
import com.pikume.back.user.auth.application.port.in.ResetPasswordUseCase;
import com.pikume.back.user.auth.application.port.in.SignUpUseCase;
import com.pikume.back.user.auth.application.port.in.VerifyEmailUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCommand;
import com.pikume.back.user.auth.application.dto.ResetPasswordCommand;
import com.pikume.back.user.auth.adapter.in.web.dto.request.EmailValidRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.request.PwdResetRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.request.SignupRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.request.VerificationEmailRequest;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.pikume.back.user.auth.application.port.in.LegacySignupProofUseCase;
import com.pikume.back.user.auth.application.port.in.QuerySignupConfigurationUseCase;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.exception.SignupFailure;
import com.pikume.back.user.auth.domain.vo.VerificationType;

@Tag(name = "Auth", description = "회원가입/이메일 인증 관련 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final LegacySignupProofUseCase legacySignupProofUseCase;
	private final VerifyEmailUseCase verifyEmailUseCase;
	private final ResetPasswordUseCase resetPasswordUseCase;
	private final QueryAllowedEmailUseCase queryAllowedEmailUseCase;
	private final QuerySignupConfigurationUseCase signupConfiguration;
	private final SignupWebCredentials signupCredentials;

	@Operation(summary = "회원가입", description = "이메일, 비밀번호, 닉네임으로 회원가입을 진행합니다.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "회원가입 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 요청")
	})
	@PostMapping("/signup")
	public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest dto, HttpServletRequest request) {
		requireLegacySignup();
		signupCredentials.requireOrigin(request);
		legacySignupProofUseCase.completeLegacy(new SignUpCommand(
				dto.getEmail(), dto.getPassword(), dto.getNickname(), dto.getFixedCharacterId()),
				signupCredentials.requireProof(request), signupCredentials.requireBinding(request));
		return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse("회원가입 성공"));
	}

	@Operation(summary = "회원가입 이메일 발송", description = "회원가입시 사용자 본인인증과 이메일 중복확인을 위해 인증코드를 이메일로 발송합니다.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "인증 이메일 발송 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 요청")
	})
	@PostMapping("/send-verification/sign-up")
	public ResponseEntity<?> sendSignUpVerificationEmail(@Valid @RequestBody VerificationEmailRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		requireLegacySignup();
		signupCredentials.requireOrigin(request);
		signupCredentials.bootstrap(request, response);
		verifyEmailUseCase.sendSignUpVerificationEmail(body.email());
		return ResponseEntity.ok(new MessageResponse("회원가입 인증 이메일이 발송되었습니다."));
	}

	@Operation(summary = "비밀번호 재설정 이메일 발송", description = "비밀번호 재설정을 위한 인증코드를 이메일로 발송합니다.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "인증 이메일 발송 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 요청")
	})
	@PostMapping("/send-verification/password-reset")
	public ResponseEntity<?> sendPasswordResetVerificationEmail(@Valid @RequestBody VerificationEmailRequest request) {
		verifyEmailUseCase.sendPasswordResetVerificationEmail(request.email());
		return ResponseEntity.ok(new MessageResponse("비밀번호 재설정 인증 이메일이 발송되었습니다."));
	}

	@Operation(summary = "이메일 인증 코드 검증", description = "사용자가 입력한 인증 코드를 검증합니다.")
	@PostMapping("/verify-code")
	public ResponseEntity<?> verifyCode(@Valid @RequestBody EmailValidRequest dto, HttpServletRequest request, HttpServletResponse response) {
		String callerBinding = null;
		if (dto.getType() == VerificationType.SIGN_UP) {
			requireLegacySignup();
			signupCredentials.requireOrigin(request);
			callerBinding = signupCredentials.requireBinding(request);
		}
		verifyEmailUseCase.verifyCode(new VerifyEmailCommand(dto.getEmail(), dto.getCode(), dto.getType()));
		if (callerBinding != null) {
			var proof = legacySignupProofUseCase.issueLegacyProof(dto.getEmail(), callerBinding);
			signupCredentials.storeProof(response, proof.proof(), proof.progress().expiresAt());
		}
		return ResponseEntity.ok(new MessageResponse("이메일 인증이 완료되었습니다."));
	}

	@Operation(summary = "비밀번호 재설정", description = "인증 이메일을 통해 비밀번호를 재설정합니다.")
	@PostMapping("/password-reset")
	public ResponseEntity<?> resetPassword(@Valid @RequestBody PwdResetRequest dto) {
		resetPasswordUseCase.resetPassword(new ResetPasswordCommand(dto.getEmail(), dto.getPassword()));
		return ResponseEntity.ok(new MessageResponse("비밀번호가 재설정되었습니다."));
	}

	@Operation(summary = "이메일 허용 여부 확인", description = "이메일이 허용된 도메인에 속하는지 확인합니다.")
	@GetMapping("/email")
	public ResponseEntity<?> isEmailAllowed(@RequestParam String email) {
		boolean allowed = queryAllowedEmailUseCase.isEmailAllowed(email);
		return ResponseEntity.ok(Map.of("allowed", allowed));
	}

	@Operation(summary = "허용된 이메일 도메인 목록 조회", description = "허용된 이메일 도메인 목록을 반환합니다.")
	@GetMapping("/email-domains")
	public ResponseEntity<List<String>> getAllowedEmailDomains() {
		return ResponseEntity.ok(queryAllowedEmailUseCase.queryAllowedEmailDomains());
	}

	private void requireLegacySignup() {
		if (!signupConfiguration.querySignupConfiguration().legacySignupEnabled()) {
			throw new SignupFlowException(SignupFailure.LEGACY_SIGNUP_DISABLED);
		}
	}
}
