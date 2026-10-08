package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.global.error.ApiProblemType;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.notification.DiscordWebhookService;
import com.pikume.back.global.notification.dto.OperationalAlert;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.pikume.back.user.auth.adapter.in.web")
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmailVerificationExceptionHandler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter RETRY_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final ProblemDetailFactory problems;
	private final ObjectProvider<DiscordWebhookService> discord;
	private final Environment environment;

	@ExceptionHandler(EmailVerificationException.class)
	public ResponseEntity<ProblemDetail> handle(EmailVerificationException error, HttpServletRequest request) {
		if (error.getReason() == EmailVerificationFailure.VERIFICATION_UNAVAILABLE) {
			sendUnavailableAlert(error, request);
		}

		HttpStatus status = status(error.getReason());
		String code = error.getReason().name();
		EmailProblem type = new EmailProblem(
				URI.create("https://api.pikume.com/problems/email-verification/"
						+ code.toLowerCase(Locale.ROOT).replace('_', '-')),
				status, status.getReasonPhrase());
		ProblemDetail problem = problems.create(type, detail(error.getReason()), request.getRequestURI());
		problem.setProperty("code", code);
		HttpHeaders headers = new HttpHeaders();
		LocalDateTime retryAt = error.getRetryAt();
		if (retryAt != null) {
			problem.setProperty("resendAvailableAt", retryAt.format(RETRY_AT_FORMAT));
			long milliseconds = Duration.between(LocalDateTime.now(KST), retryAt).toMillis();
			if (milliseconds > 0) {
				headers.set(HttpHeaders.RETRY_AFTER, Long.toString((milliseconds + 999) / 1000));
			}
		}
		return ResponseEntity.status(status)
				.headers(headers)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body(problem);
	}

	private void sendUnavailableAlert(EmailVerificationException error, HttpServletRequest request) {
		try {
			String errorType = error.getCause() == null
					? "RedisOperationFailure" : error.getCause().getClass().getSimpleName();
			String[] profiles = environment.getActiveProfiles();
			String profile = profiles.length == 0 ? "default" : String.join(",", profiles);
			discord.ifAvailable(service -> service.sendOperationalAlert(new OperationalAlert(
					"회원가입 이메일 인증 Redis 장애", profile, LocalDateTime.now(KST),
					request.getRequestURI(), request.getMethod(), error.getProcessingStage(),
					errorType, HttpStatus.SERVICE_UNAVAILABLE.value())));
		} catch (RuntimeException alertFailure) {
			log.error("event=email_verification_alert outcome=failed errorType={}",
					alertFailure.getClass().getSimpleName());
		}
	}

	private static HttpStatus status(EmailVerificationFailure reason) {
		return switch (reason) {
			case VERIFICATION_UNAVAILABLE, EMAIL_SEND_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
			case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
			case EMAIL_ALREADY_EXISTS -> HttpStatus.CONFLICT;
			default -> HttpStatus.BAD_REQUEST;
		};
	}

	private static String detail(EmailVerificationFailure reason) {
		return switch (reason) {
			case INVALID_EMAIL -> "가입에 사용할 수 없는 이메일입니다.";
			case CODE_MISMATCH -> "인증 코드가 일치하지 않습니다.";
			case CODE_EXPIRED -> "인증 코드가 만료되었습니다. 다시 인증해주세요.";
			case EMAIL_SEND_FAILED -> "인증 이메일 발송에 실패했습니다. 다시 시도해주세요.";
			case VERIFICATION_UNAVAILABLE -> "이메일 인증을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.";
			case EMAIL_ALREADY_EXISTS -> "이미 가입된 이메일입니다.";
			case RATE_LIMITED -> "잠시 후 다시 시도해주세요.";
			case ATTEMPTS_EXHAUSTED -> "인증 시도 횟수가 끝났습니다. 이메일 인증 코드를 다시 요청해주세요.";
			default -> "이메일 인증 정보를 확인해주세요.";
		};
	}

	private record EmailProblem(URI type, HttpStatus status, String title) implements ApiProblemType {}
}
