package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.global.error.ApiProblemType;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Locale;

@RestControllerAdvice(basePackages = "com.pikume.back.user.auth.adapter.in.web")
@RequiredArgsConstructor
public class EmailVerificationExceptionHandler {

	private final ProblemDetailFactory problems;

	@ExceptionHandler(EmailVerificationException.class)
	public ResponseEntity<ProblemDetail> handle(EmailVerificationException error, HttpServletRequest request) {
		HttpStatus status = switch (error.getReason()) {
			case EMAIL_SEND_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
			case CODE_EXPIRED, TOKEN_EXPIRED -> HttpStatus.GONE;
			case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
			case VERIFICATION_ALREADY_COMPLETED, TOKEN_ALREADY_USED -> HttpStatus.CONFLICT;
			default -> HttpStatus.BAD_REQUEST;
		};
		String detail = switch (error.getReason()) {
			case INVALID_EMAIL -> "가입에 사용할 수 없는 이메일입니다.";
			case CODE_MISMATCH -> "인증 코드가 일치하지 않습니다.";
			case CODE_EXPIRED -> "인증 코드가 만료되었습니다.";
			case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";
			case EMAIL_SEND_FAILED -> "인증 이메일을 발송하지 못했습니다. 다시 요청해주세요.";
			case VERIFICATION_ALREADY_COMPLETED -> "이미 인증이 완료된 이메일 요청입니다.";
			case TOKEN_EXPIRED -> "이메일 인증이 만료되었습니다. 다시 인증해주세요.";
			case TOKEN_ALREADY_USED -> "이미 사용된 이메일 인증입니다.";
			default -> "이메일 인증 정보를 확인해주세요.";
		};
		String code = error.getReason().name();
		var type = new EmailProblem(
				URI.create("https://api.pikume.com/problems/email-verification/"
						+ code.toLowerCase(Locale.ROOT).replace('_', '-')),
				status, status.getReasonPhrase());
		var problem = problems.create(type, detail, request.getRequestURI());
		problem.setProperty("code", code);
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore())
				.body(problem);
	}

	private record EmailProblem(URI type, HttpStatus status, String title) implements ApiProblemType {}
}
