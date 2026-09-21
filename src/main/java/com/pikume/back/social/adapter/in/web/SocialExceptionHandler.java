package com.pikume.back.social.adapter.in.web;

import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.social.adapter.in.web.problem.SocialProblemType;
import com.pikume.back.social.application.exception.SocialException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(assignableTypes = { LikeController.class, CommentController.class, FriendController.class })
@RequiredArgsConstructor
public class SocialExceptionHandler {

	private final ProblemDetailFactory problemDetailFactory;

	@ExceptionHandler(SocialException.class)
	public ResponseEntity<ProblemDetail> handleSocialException(SocialException exception, HttpServletRequest request) {
		SocialProblemType problemType = SocialProblemType.from(exception.getErrorCode());
		log.warn("event=social_request_failed outcome=denied reason={} status={}",
				exception.getErrorCode(), problemType.status().value());
		ProblemDetail problemDetail = problemDetailFactory.create(
				problemType, exception.getMessage(), request.getRequestURI());
		return ResponseEntity.status(problemType.status()).body(problemDetail);
	}
}
