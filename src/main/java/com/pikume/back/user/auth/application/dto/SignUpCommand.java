package com.pikume.back.user.auth.application.dto;

public record SignUpCommand(String email, String password, String nickname, Long fixedCharacterId,
		String emailVerificationToken) {

	@Override
	public String toString() {
		return "SignUpCommand[email=" + email + ", password=[REDACTED], nickname=" + nickname
				+ ", fixedCharacterId=" + fixedCharacterId + ", emailVerificationToken=[REDACTED]]";
	}
}
