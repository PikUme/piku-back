package com.pikume.back.user.domain.vo;

import com.pikume.back.user.domain.exception.InvalidNicknameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Nickname Value Object")
class NicknameTest {

	@Nested
	@DisplayName("생성")
	class Creation {

		@Test
		@DisplayName("유효한 닉네임으로 생성할 수 있다")
		void validNickname() {
			Nickname nickname = new Nickname("피쿠유저");
			assertThat(nickname.value()).isEqualTo("피쿠유저");
		}

		@Test
		@DisplayName("앞뒤 ASCII 공백을 제거한다")
		void stripsAsciiWhitespace() {
			Nickname nickname = new Nickname(" \t피쿠유저\n ");

			assertThat(nickname.value()).isEqualTo("피쿠유저");
		}

		@Test
		@DisplayName("앞뒤 Unicode 공백을 제거한다")
		void stripsUnicodeWhitespace() {
			Nickname nickname = new Nickname("\u2003피쿠유저\u3000");

			assertThat(nickname.value()).isEqualTo("피쿠유저");
		}

		@Test
		@DisplayName("닉네임 중간 공백을 유지한다")
		void preservesInternalWhitespace() {
			Nickname nickname = new Nickname(" 피쿠\u2003유저 ");

			assertThat(nickname.value()).isEqualTo("피쿠\u2003유저");
		}

		@Test
		@DisplayName("정규화 후 한 글자인 닉네임을 허용한다")
		void acceptsMinimumNormalizedLength() {
			Nickname nickname = new Nickname(" \u2003픽\u3000 ");

			assertThat(nickname.value()).isEqualTo("픽");
		}

		@Test
		@DisplayName("정규화 후 스무 글자인 닉네임을 허용한다")
		void acceptsMaximumNormalizedLength() {
			String twentyCharacters = "가".repeat(20);

			Nickname nickname = new Nickname(" \u2003" + twentyCharacters + "\u3000 ");

			assertThat(nickname.value()).isEqualTo(twentyCharacters);
		}

		@Test
		@DisplayName("정규화 후 스물한 글자인 닉네임을 거절한다")
		void rejectsOverMaximumNormalizedLength() {
			assertThatThrownBy(() -> new Nickname(" \u2003" + "가".repeat(21) + "\u3000 "))
					.isInstanceOf(InvalidNicknameException.class);
		}

		@Test
		@DisplayName("null 닉네임은 예외를 발생시킨다")
		void nullNickname() {
			assertThatThrownBy(() -> new Nickname(null))
					.isInstanceOf(InvalidNicknameException.class);
		}

		@Test
		@DisplayName("빈 닉네임은 예외를 발생시킨다")
		void emptyNickname() {
			assertThatThrownBy(() -> new Nickname(""))
					.isInstanceOf(InvalidNicknameException.class);
		}

		@Test
		@DisplayName("공백만 있는 닉네임은 예외를 발생시킨다")
		void blankNickname() {
			assertThatThrownBy(() -> new Nickname("   "))
					.isInstanceOf(InvalidNicknameException.class);
		}
	}

	@Nested
	@DisplayName("동등성")
	class Equality {

		@Test
		@DisplayName("같은 값이면 동등하다")
		void equalNicknames() {
			Nickname a = new Nickname("피쿠");
			Nickname b = new Nickname("피쿠");
			assertThat(a).isEqualTo(b);
			assertThat(a.hashCode()).isEqualTo(b.hashCode());
		}

		@Test
		@DisplayName("앞뒤 공백만 다른 입력은 동등하다")
		void whitespaceVariantsAreEqual() {
			Nickname plain = new Nickname("피쿠");
			Nickname padded = new Nickname(" \u2003피쿠\u3000 ");

			assertThat(padded).isEqualTo(plain);
			assertThat(padded.hashCode()).isEqualTo(plain.hashCode());
		}

		@Test
		@DisplayName("다른 값이면 동등하지 않다")
		void differentNicknames() {
			Nickname a = new Nickname("피쿠A");
			Nickname b = new Nickname("피쿠B");
			assertThat(a).isNotEqualTo(b);
		}
	}
}
