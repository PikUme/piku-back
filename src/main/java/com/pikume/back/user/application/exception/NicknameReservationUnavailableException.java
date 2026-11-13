package com.pikume.back.user.application.exception;

public class NicknameReservationUnavailableException extends RuntimeException {

	private final String stage;

	public NicknameReservationUnavailableException(String stage, Throwable cause) {
		super("Nickname reservation is unavailable", cause);
		this.stage = stage;
	}

	public String getStage() {
		return stage;
	}
}
