package com.pikume.back.user.auth.application.exception;

import lombok.Getter;

@Getter
public class EmailVerificationException extends RuntimeException {

	private final EmailVerificationFailure reason;
	private final String processingStage;

	public EmailVerificationException(EmailVerificationFailure reason) {
		this(reason, null, null);
	}

	public EmailVerificationException(EmailVerificationFailure reason, Throwable cause, String processingStage) {
		super(reason.name(), cause);
		this.reason = reason;
		this.processingStage = processingStage;
	}
}
