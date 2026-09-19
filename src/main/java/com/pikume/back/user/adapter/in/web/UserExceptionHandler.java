package com.pikume.back.user.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.error.ValidationProblemType;
import com.pikume.back.user.adapter.in.web.problem.UserProblemType;
import com.pikume.back.user.application.exception.UserException;
import com.pikume.back.user.application.exception.ProfileImageNotFoundException;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.exception.InvalidNicknameException;
import com.pikume.back.global.error.CommonProblemType;

@RestControllerAdvice
@RequiredArgsConstructor
public class UserExceptionHandler {

	private final ProblemDetailFactory problemDetailFactory;

	@ExceptionHandler(UserException.class)
	public ResponseEntity<ProblemDetail> handleUserException(UserException ex, HttpServletRequest request) {
		UserProblemType problemType = UserProblemType.from(ex.getErrorCode());
		String detail = problemType.status().is5xxServerError()
				? ex.getErrorCode().getMessage()
				: ex.getMessage();
		ProblemDetail problemDetail = problemDetailFactory.create(problemType, detail, request.getRequestURI());
		return ResponseEntity.status(problemType.status()).body(problemDetail);
	}

	@ExceptionHandler(NicknameAlreadyExistsException.class)
	public ResponseEntity<ProblemDetail> handleNicknameAlreadyExists(
			NicknameAlreadyExistsException ignored, HttpServletRequest request) {
		ProblemDetail detail = problemDetailFactory.create(
				UserProblemType.NICKNAME_CONFLICT,
				"이미 사용 중인 닉네임입니다.",
				request.getRequestURI());
		return ResponseEntity.status(UserProblemType.NICKNAME_CONFLICT.status()).body(detail);
	}

	@ExceptionHandler(InvalidNicknameException.class)
	public ResponseEntity<ProblemDetail> handleInvalidNickname(
			InvalidNicknameException exception, HttpServletRequest request) {
		ProblemDetail detail = problemDetailFactory.create(
				ValidationProblemType.INVALID_REQUEST,
				exception.getMessage(),
				request.getRequestURI());
		return ResponseEntity.status(ValidationProblemType.INVALID_REQUEST.status()).body(detail);
	}

	@ExceptionHandler(ProfileImageNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleProfileImageNotFound(
			ProfileImageNotFoundException exception, HttpServletRequest request) {
		ProblemDetail detail = problemDetailFactory.create(
				CommonProblemType.RESOURCE_NOT_FOUND,
				"존재하지 않는 프로필 이미지입니다.",
				request.getRequestURI());
		return ResponseEntity.status(CommonProblemType.RESOURCE_NOT_FOUND.status()).body(detail);
	}
}
