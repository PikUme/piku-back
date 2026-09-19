package com.pikume.back.user.domain.vo;

import com.pikume.back.user.domain.exception.InvalidNicknameException;

/**
 * 닉네임 Value Object
 * 닉네임 검증 규칙(길이, 형식)을 캡슐화합니다.
 */
public record Nickname(String value) {

	private static final int MIN_LENGTH = 1;
	private static final int MAX_LENGTH = 20;

	public Nickname {
		if (value == null) {
			throw new InvalidNicknameException("닉네임은 필수 값입니다.");
		}
		value = value.strip();
		if (value.isEmpty()) {
			throw new InvalidNicknameException("닉네임은 필수 값입니다.");
		}
		if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
			throw new InvalidNicknameException(
					String.format("닉네임은 %d~%d자 사이여야 합니다.", MIN_LENGTH, MAX_LENGTH));
		}
	}
}
