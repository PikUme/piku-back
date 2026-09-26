package com.pikume.back.user.application.exception;

public class SignupProfileException extends RuntimeException {
	private final SignupProfileFailure failure;

	public SignupProfileException(SignupProfileFailure failure) {
		super(failure.name());
		this.failure = failure;
	}

	public SignupProfileFailure getFailure() {
		return failure;
	}
}
