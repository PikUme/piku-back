package com.pikume.back.security.adapter.in.web.problem;

import com.pikume.back.admin.application.exception.AdminErrorCode;
import com.pikume.back.global.error.ApiProblemType;
import org.springframework.http.HttpStatus;

import java.net.URI;

public enum SecurityProblemType implements ApiProblemType {
	PROFILE_SETUP_REQUIRED("https://api.pikume.com/problems/signup/profile-setup-required", HttpStatus.FORBIDDEN, "Forbidden"),
	UNAUTHENTICATED("https://api.pikume.com/problems/security/unauthenticated", HttpStatus.UNAUTHORIZED, "Unauthorized"),
	INVALID_CREDENTIALS("https://api.pikume.com/problems/security/invalid-credentials", HttpStatus.UNAUTHORIZED, "Unauthorized"),
	INVALID_REFRESH_TOKEN("https://api.pikume.com/problems/security/invalid-refresh-token", HttpStatus.UNAUTHORIZED, "Unauthorized"),
	ADMIN_UNAUTHENTICATED(
			"https://api.pikume.com/problems/admin/unauthenticated",
			HttpStatus.UNAUTHORIZED,
			"Unauthorized"),
	ADMIN_CSRF_INVALID(
			"https://api.pikume.com/problems/admin/csrf-invalid",
			HttpStatus.FORBIDDEN,
			"Forbidden"),
	ADMIN_ORIGIN_FORBIDDEN("https://api.pikume.com/problems/admin/origin-forbidden", HttpStatus.FORBIDDEN, "Forbidden"),
	ADMIN_SESSION_STORE_UNAVAILABLE(
			"https://api.pikume.com/problems/admin/session-store-unavailable",
			HttpStatus.SERVICE_UNAVAILABLE,
			"Service Unavailable"),
	FORBIDDEN("https://api.pikume.com/problems/security/forbidden", HttpStatus.FORBIDDEN, "Forbidden");

	private final URI type;
	private final HttpStatus status;
	private final String title;

	SecurityProblemType(String type, HttpStatus status, String title) {
		this.type = URI.create(type);
		this.status = status;
		this.title = title;
	}

	@Override
	public URI type() {
		return type;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String title() {
		return title;
	}

	public static SecurityProblemType fromAdminErrorCode(AdminErrorCode errorCode) {
		return switch (errorCode) {
			case UNAUTHENTICATED -> ADMIN_UNAUTHENTICATED;
			case CSRF_INVALID -> ADMIN_CSRF_INVALID;
			case SESSION_STORE_UNAVAILABLE -> ADMIN_SESSION_STORE_UNAVAILABLE;
			default -> throw new IllegalArgumentException(
					"Security Filter가 처리할 수 없는 Admin 오류입니다: " + errorCode);
		};
	}
}
