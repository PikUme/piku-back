package com.pikume.back.user.adapter.in.web;

import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.user.adapter.in.web.problem.NicknameReservationProblemType;
import com.pikume.back.user.auth.adapter.in.web.SignupNicknameReservationController;
import com.pikume.back.user.auth.adapter.in.web.AuthController;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.application.exception.NicknameReservationTokenInvalidException;
import com.pikume.back.user.application.exception.SignupEmailAlreadyExistsException;
import com.pikume.back.user.application.exception.NicknameReservationUnavailableException;
import com.pikume.back.user.adapter.in.web.UserController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(assignableTypes = {SignupNicknameReservationController.class, AuthController.class, UserController.class})
@RequiredArgsConstructor
public class NicknameReservationExceptionHandler {

	private final ProblemDetailFactory problems;

	@ExceptionHandler(NicknameReservationTokenInvalidException.class)
	public ResponseEntity<ProblemDetail> handleInvalidToken(
			NicknameReservationTokenInvalidException exception, HttpServletRequest request) {
		return problem(NicknameReservationProblemType.TOKEN_INVALID, "이메일 인증 정보를 확인해주세요.", request);
	}

	@ExceptionHandler(NicknameReservationConflictException.class)
	public ResponseEntity<ProblemDetail> handleConflict(
			NicknameReservationConflictException exception, HttpServletRequest request) {
		return problem(NicknameReservationProblemType.NICKNAME_CONFLICT, "이미 사용 중인 닉네임입니다.", request);
	}

	@ExceptionHandler(SignupEmailAlreadyExistsException.class)
	public ResponseEntity<ProblemDetail> handleEmailAlreadyExists(
			SignupEmailAlreadyExistsException exception, HttpServletRequest request) {
		return problem(NicknameReservationProblemType.EMAIL_ALREADY_EXISTS, "이미 가입된 이메일입니다.", request);
	}

	@ExceptionHandler(NicknameReservationUnavailableException.class)
	public ResponseEntity<ProblemDetail> handleUnavailable(
			NicknameReservationUnavailableException exception, HttpServletRequest request) {
		String errorType = exception.getCause() == null
				? "Unknown" : exception.getCause().getClass().getSimpleName();
		log.error("event=nickname_reservation outcome=unavailable stage={} errorType={}",
				exception.getStage(), errorType);
		return problem(NicknameReservationProblemType.UNAVAILABLE,
				"닉네임 예약을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.", request);
	}

	private ResponseEntity<ProblemDetail> problem(
			NicknameReservationProblemType type, String detail, HttpServletRequest request) {
		ProblemDetail problem = problems.create(type, detail, request.getRequestURI());
		problem.setProperty("code", switch (type) {
			case TOKEN_INVALID -> "TOKEN_INVALID";
			case EMAIL_ALREADY_EXISTS -> "EMAIL_ALREADY_EXISTS";
			case NICKNAME_CONFLICT -> "NICKNAME_CONFLICT";
			case UNAVAILABLE -> "NICKNAME_RESERVATION_UNAVAILABLE";
		});
		return ResponseEntity.status(type.status()).body(problem);
	}
}
