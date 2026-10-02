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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

@RestControllerAdvice(basePackages = "com.pikume.back.user.auth.adapter.in.web")
@RequiredArgsConstructor
public class EmailVerificationExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(EmailVerificationExceptionHandler.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final ProblemDetailFactory problems;
	private final ObjectProvider<DiscordWebhookService> discord;
	private final Environment environment;

	@ExceptionHandler(EmailVerificationException.class)
	public ResponseEntity<ProblemDetail> handle(EmailVerificationException error, HttpServletRequest request) {
		if (error.getReason() == EmailVerificationFailure.VERIFICATION_UNAVAILABLE) {
			try {
				String errorType = error.getCause() == null
						? "RedisOperationFailure" : error.getCause().getClass().getSimpleName();
				String[] activeProfiles = environment.getActiveProfiles();
				String profile = activeProfiles.length == 0 ? "default" : String.join(",", activeProfiles);
				discord.ifAvailable(service -> service.sendOperationalAlert(new OperationalAlert(
						"회원가입 이메일 인증 Redis 장애", profile, LocalDateTime.now(KST),
						request.getRequestURI(), request.getMethod(), error.getProcessingStage(),
						errorType, HttpStatus.SERVICE_UNAVAILABLE.value())));
			} catch (RuntimeException alertFailure) {
				log.error("event=email_verification_alert outcome=failed errorType={}",
						alertFailure.getClass().getSimpleName());
			}
		}

		HttpStatus status = status(error.getReason());
		String code = error.getReason().name();
		var type = new EmailProblem(
				URI.create("https://api.pikume.com/problems/email-verification/"
						+ code.toLowerCase(Locale.ROOT).replace('_', '-')),
				status, status.getReasonPhrase());
		var problem = problems.create(type, detail(error.getReason()), request.getRequestURI());
		problem.setProperty("code", code);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore());
		if (error.getRetryAt() != null) {
			problem.setProperty("resendAvailableAt", error.getRetryAt());
			ZonedDateTime retryAt = error.getRetryAt().atZone(KST).withZoneSameInstant(ZoneOffset.UTC);
			response.header(HttpHeaders.RETRY_AFTER,
					DateTimeFormatter.RFC_1123_DATE_TIME.format(retryAt));
		}
		return response.body(problem);
	}

	private static HttpStatus status(EmailVerificationFailure reason) {
		return switch (reason) {
			case EMAIL_SEND_FAILED, VERIFICATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
			case EMAIL_ALREADY_EXISTS, TOKEN_ALREADY_USED, VERIFICATION_ALREADY_COMPLETED -> HttpStatus.CONFLICT;
			default -> HttpStatus.BAD_REQUEST;
		};
	}

	private static String detail(EmailVerificationFailure reason) {
		return switch (reason) {
			case INVALID_EMAIL -> "가입에 사용할 수 없는 이메일입니다.";
			case CODE_MISMATCH -> "인증 코드가 일치하지 않습니다.";
			case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";
			case EMAIL_SEND_FAILED -> "인증 이메일 발송 결과를 확인할 수 없습니다. 잠시 후 다시 요청해주세요.";
			case VERIFICATION_UNAVAILABLE -> "이메일 인증을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.";
			case EMAIL_ALREADY_EXISTS -> "이미 가입된 이메일입니다.";
			case TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_ALREADY_USED -> "이메일 인증이 만료되었거나 유효하지 않습니다. 다시 인증해주세요.";
			default -> "이메일 인증 정보를 확인해주세요.";
		};
	}

	private record EmailProblem(URI type, HttpStatus status, String title) implements ApiProblemType {}
}
