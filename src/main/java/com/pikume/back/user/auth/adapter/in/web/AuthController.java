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
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.dto.SignUpCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCommand;
import com.pikume.back.user.auth.application.dto.ResetPasswordCommand;
import com.pikume.back.user.auth.application.dto.SignupEmailVerification;
import com.pikume.back.user.auth.adapter.in.web.dto.request.EmailValidRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.request.PwdResetRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.request.SignupRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.request.VerificationEmailRequest;
import com.pikume.back.user.auth.adapter.in.web.dto.response.SignupEmailVerificationResponse;
import com.pikume.back.user.auth.adapter.in.web.dto.response.SignupVerificationEmailResponse;

import java.util.List;
import java.util.Map;

@Tag(name = "Auth", description = "회원가입/이메일 인증 관련 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final SignUpUseCase signUpUseCase;
	private final VerifyEmailUseCase verifyEmailUseCase;
	private final EmailVerificationUseCase emailVerificationUseCase;
	private final ResetPasswordUseCase resetPasswordUseCase;
	private final QueryAllowedEmailUseCase queryAllowedEmailUseCase;

	@Operation(summary = "회원가입", description = "이메일, 비밀번호, 닉네임으로 회원가입을 진행합니다.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "회원가입 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 요청")
	})
	@PostMapping("/signup")
	public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest dto) {
		signUpUseCase.signUp(new SignUpCommand(
				dto.getEmail(), dto.getPassword(), dto.getNickname(), dto.getFixedCharacterId(),
				dto.getEmailVerificationToken()));
		return ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse("회원가입 성공"));
	}

	@Operation(summary = "회원가입 이메일 발송", description = "회원가입시 사용자 본인인증과 이메일 중복확인을 위해 인증코드를 이메일로 발송합니다.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "인증 이메일 발송 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 요청"),
			@ApiResponse(responseCode = "503", description = "이메일 인증 저장소 또는 메일 발송을 사용할 수 없음")
	})
	@PostMapping("/send-verification/sign-up")
	public ResponseEntity<?> sendSignUpVerificationEmail(@Valid @RequestBody VerificationEmailRequest request) {
		var sent = emailVerificationUseCase.sendSignUpVerificationEmail(request.email());
		return ResponseEntity.ok(new SignupVerificationEmailResponse(
				"회원가입 인증 이메일이 발송되었습니다.",
				sent.expiresAt(), sent.resendAvailableAt()));
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
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "이메일 인증 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 코드 또는 요청"),
			@ApiResponse(responseCode = "503", description = "이메일 인증 저장소를 사용할 수 없음")
	})
	@PostMapping("/verify-code")
	public ResponseEntity<?> verifyCode(@Valid @RequestBody EmailValidRequest dto) {
		if (dto.getType() == VerificationType.SIGN_UP) {
			SignupEmailVerification verification = emailVerificationUseCase.verifySignUpVerificationCode(
					dto.getEmail(), dto.getCode());
			return ResponseEntity.ok(new SignupEmailVerificationResponse(
					"이메일 인증이 완료되었습니다.", verification.token(), verification.expiresAt()));
		} else {
			verifyEmailUseCase.verifyCode(new VerifyEmailCommand(dto.getEmail(), dto.getCode(), dto.getType()));
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
}
