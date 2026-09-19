package com.pikume.back.user.domain.exception;

public class InvalidNicknameException extends IllegalArgumentException {

	public InvalidNicknameException(String message) {
		super(message);
	}
}
