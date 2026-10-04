package com.pikume.back.user.auth.application.exception;

import lombok.Getter;
import java.time.LocalDateTime;

@Getter
public class EmailVerificationException extends RuntimeException {

	private final EmailVerificationFailure reason;
	private final String processingStage;
	private final LocalDateTime retryAt;

	public EmailVerificationException(EmailVerificationFailure reason) {
		this(reason, null, null, null);
	}

	public EmailVerificationException(EmailVerificationFailure reason, Throwable cause, String processingStage) {
		this(reason, cause, processingStage, null);
	}

	public EmailVerificationException(EmailVerificationFailure reason, LocalDateTime retryAt) {
		this(reason, null, null, retryAt);
	}

	public EmailVerificationException(EmailVerificationFailure reason, Throwable cause, String processingStage,
			LocalDateTime retryAt) {
		super(reason.name(), cause);
		this.reason = reason;
		this.processingStage = processingStage;
		this.retryAt = retryAt;
	}
}
