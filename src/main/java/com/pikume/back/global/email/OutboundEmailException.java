package com.pikume.back.global.email;

public class OutboundEmailException extends RuntimeException {

	private final String provider;
	private final String category;
	private final Integer statusCode;

	public OutboundEmailException(String provider, String category, Integer statusCode) {
		super("Email delivery failed");
		this.provider = provider;
		this.category = category;
		this.statusCode = statusCode;
	}

	public String provider() {
		return provider;
	}

	public String category() {
		return category;
	}

	public Integer statusCode() {
		return statusCode;
	}
}
