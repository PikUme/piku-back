package com.pikume.back.user.adapter.in.web.problem;

import com.pikume.back.global.error.ApiProblemType;
import org.springframework.http.HttpStatus;

import java.net.URI;

public enum NicknameReservationProblemType implements ApiProblemType {
	TOKEN_INVALID("https://api.pikume.com/problems/nickname-reservation/token-invalid", HttpStatus.BAD_REQUEST, "Bad Request"),
	EMAIL_ALREADY_EXISTS("https://api.pikume.com/problems/auth/email-already-exists", HttpStatus.CONFLICT, "Conflict"),
	NICKNAME_CONFLICT("https://api.pikume.com/problems/user/nickname-conflict", HttpStatus.CONFLICT, "Conflict"),
	UNAVAILABLE("https://api.pikume.com/problems/nickname-reservation/unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable");

	private final URI type;
	private final HttpStatus status;
	private final String title;

	NicknameReservationProblemType(String type, HttpStatus status, String title) {
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
}
