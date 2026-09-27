package com.pikume.back.user.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSignupNicknamePolicyTest {

	@ParameterizedTest
	@CsvSource({
		"haru@example.com,0,haru",
		"가입대기_user@example.com,0,가입대기_user",
		"Haru.note+tag@example.com,0,Haru.note+tag",
		"abcdefghijklmnopqrstuv@example.com,0,abcdefghijklmnopqrst",
		"haru@example.com,4821,haru4821",
		"abcdefghijklmnopqrstuv@example.com,4821,abcdefghijklmnop4821"
	})
	void derivesDefaultFromLocalPartWithoutExposingDomain(String email,int suffix,String expected) {
		assertThat(DefaultSignupNicknamePolicy.candidate(email,suffix).value()).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings={"@example.com"," \t@example.com"})
	void unusableLocalPartUsesSafeVisibleFallback(String email) {
		assertThat(DefaultSignupNicknamePolicy.candidate(email,0).value()).isEqualTo("사용자");
		assertThat(DefaultSignupNicknamePolicy.candidate(email,4821).value()).isEqualTo("사용자4821");
	}
}
