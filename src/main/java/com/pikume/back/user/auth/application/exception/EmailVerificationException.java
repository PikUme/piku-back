package com.pikume.back.user.auth.application.exception;

import java.time.LocalDateTime;

public class EmailVerificationException extends RuntimeException {

	private final EmailVerificationFailure reason;
	private final LocalDateTime retryAt;
	private final String processingStage;

	public EmailVerificationException(EmailVerificationFailure reason) {
		super(reason.name());
		this.reason = reason;
		this.retryAt = null;
		this.processingStage = null;
	}

	public EmailVerificationException(EmailVerificationFailure reason, LocalDateTime retryAt) {
		super(reason.name());
		this.reason = reason;
		this.retryAt = retryAt;
		this.processingStage = null;
	}

	public EmailVerificationException(EmailVerificationFailure reason, LocalDateTime retryAt, Throwable cause) {
		super(reason.name(), cause);
		this.reason = reason;
		this.retryAt = retryAt;
		this.processingStage = null;
	}

	public EmailVerificationException(EmailVerificationFailure reason, Throwable cause) {
		super(reason.name(), cause);
		this.reason = reason;
		this.retryAt = null;
		this.processingStage = null;
	}

	public EmailVerificationException(EmailVerificationFailure reason, Throwable cause, String processingStage) {
		super(reason.name(), cause);
		this.reason = reason;
		this.retryAt = null;
		this.processingStage = processingStage;
	}

	public EmailVerificationFailure getReason() {
		return reason;
	}

	public LocalDateTime getRetryAt() { return retryAt; }
	public String getProcessingStage() { return processingStage; }
}
