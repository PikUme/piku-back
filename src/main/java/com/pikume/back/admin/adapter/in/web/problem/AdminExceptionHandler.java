package com.pikume.back.admin.adapter.in.web.problem;

import com.pikume.back.admin.application.exception.AdminAuthenticationStoreException;
import com.pikume.back.admin.application.exception.AdminException;
import com.pikume.back.admin.application.port.in.RecordAdminSecurityEventUseCase;
import com.pikume.back.admin.domain.exception.AdminDomainException;
import com.pikume.back.global.error.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.pikume.back.admin.adapter.in.web")
@RequiredArgsConstructor
public class AdminExceptionHandler {

	private final ProblemDetailFactory problemDetailFactory;
	private final RecordAdminSecurityEventUseCase recordAdminSecurityEventUseCase;

	@ExceptionHandler(AdminException.class)
	public ResponseEntity<ProblemDetail> handleAdminException(AdminException exception, HttpServletRequest request) {
		AdminProblemType problemType = AdminProblemType.from(exception.errorCode());
		ProblemDetail problemDetail = problemDetailFactory.create(
				problemType,
				exception.getMessage(),
				request.getRequestURI());
		return ResponseEntity.status(problemType.status())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(problemDetail);
	}

	@ExceptionHandler(AdminDomainException.class)
	public ResponseEntity<ProblemDetail> handleAdminDomainException(AdminDomainException exception, HttpServletRequest request) {
		ProblemDetail problemDetail = problemDetailFactory.create(
				AdminProblemType.INVALID_REQUEST,
				exception.getMessage(),
				request.getRequestURI());
		return ResponseEntity.status(AdminProblemType.INVALID_REQUEST.status())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(problemDetail);
	}

	@ExceptionHandler(AdminAuthenticationStoreException.class)
	public ResponseEntity<ProblemDetail> handleStoreUnavailable(
			AdminAuthenticationStoreException exception, HttpServletRequest request) {
		recordAdminSecurityEventUseCase.recordSessionStoreUnavailable();
		ProblemDetail problemDetail = problemDetailFactory.create(
				AdminProblemType.SESSION_STORE_UNAVAILABLE,
				"관리자 인증 저장소를 확인할 수 없습니다.",
				request.getRequestURI());
		return ResponseEntity.status(AdminProblemType.SESSION_STORE_UNAVAILABLE.status())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(problemDetail);
	}
}
