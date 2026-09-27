package com.pikume.back.user.domain;

import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;
import com.pikume.back.user.domain.exception.InvalidNicknameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("User")
class UserTest {

	private static final String DEFAULT_NICKNAME = "user";

	@Test
	@DisplayName("이메일과 닉네임은 값 객체로, 아바타는 캐릭터 식별자로 보유한다")
	void ownsProfileValueObjects() throws NoSuchFieldException {
		assertThat(User.class.getDeclaredField("email").getType()).isEqualTo(Email.class);
		assertThat(User.class.getDeclaredField("nickname").getType()).isEqualTo(Nickname.class);
		assertThat(User.class.getDeclaredField("characterId").getType()).isEqualTo(Long.class);
	}

	@Test
	@DisplayName("유효하지 않은 이메일로 사용자를 생성할 수 없다")
	void rejectsInvalidEmail() {
		assertThatThrownBy(() -> new User("invalid-email", "password", "nickname", 1L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("유효하지 않은 닉네임으로 사용자를 생성할 수 없다")
	void rejectsInvalidNickname() {
		assertThatThrownBy(() -> new User("user@example.com", "password", " ", 1L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("캐릭터 식별자 없이 사용자를 생성할 수 없다")
	void rejectsMissingCharacterIdentifier() {
		assertThatThrownBy(() -> new User("user@example.com", "password", "nickname", (Long) null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new User("user@example.com", "password", "nickname", 0L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("유효하지 않은 닉네임으로 변경할 수 없다")
	void rejectsInvalidNicknameChange() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		assertThatThrownBy(() -> user.changeNickname(" "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("완료 사용자는 일반 닉네임 규칙에 맞는 값으로 변경할 수 있다")
	void completedUserCanChangeNickname() {
		User user = new User("user@example.com", "password", "user", 1L);

		user.changeNickname(" renamed ");

		assertThat(user.getNickname()).isEqualTo("renamed");
	}

	@Test
	@DisplayName("회원 탈퇴 시 탈퇴 처리 시각을 기록한다")
	void recordsWithdrawnAtWhenUserWithdraws() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		user.withdraw();

		assertThat(user.getDeletedAt()).isNotNull();
		assertThat(user.isWithdrawn()).isTrue();
	}

	@Test
	@DisplayName("캡슐화된 행위로 닉네임과 아바타 캐릭터 식별자를 변경한다")
	void changesProfileThroughAggregateBehavior() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		user.changeNickname("new-nickname");
		user.changeCharacter(2L);

		assertThat(user.getNickname()).isEqualTo("new-nickname");
		assertThat(user.getCharacterId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("검증된 닉네임 값 객체로 사용자를 생성한다")
	void createsUserWithValidatedNickname() {
		User user = new User("user@example.com", "password", new Nickname(" \u2003피쿠\u3000 "), 1L);

		assertThat(user.getNickname()).isEqualTo("피쿠");
	}

	@Test
	@DisplayName("닉네임 값 객체 없이 사용자를 생성할 수 없다")
	void rejectsMissingValidatedNickname() {
		assertThatThrownBy(() -> new User("user@example.com", "password", (Nickname) null, 1L))
				.isInstanceOf(InvalidNicknameException.class);
	}

	@Test
	@DisplayName("검증된 닉네임 값 객체로 닉네임을 변경한다")
	void changesNicknameWithValidatedNickname() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		user.changeNickname(new Nickname(" \u2003새닉\u3000 "));

		assertThat(user.getNickname()).isEqualTo("새닉");
	}

	@Test
	@DisplayName("닉네임 값 객체 없이 닉네임을 변경할 수 없다")
	void rejectsMissingValidatedNicknameChange() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		assertThatThrownBy(() -> user.changeNickname((Nickname) null))
				.isInstanceOf(InvalidNicknameException.class);
	}

	@Test
	@DisplayName("캡슐화된 행위로 보호된 비밀번호를 변경한다")
	void changesProtectedPasswordThroughAggregateBehavior() {
		User user = new User("user@example.com", "old-password", "nickname", 1L);

		user.updatePassword("new-password");

		assertThat(user.getPassword()).isEqualTo("new-password");
	}

	@Test
	@DisplayName("기존 생성자로 만든 사용자는 프로필 설정 완료 상태다")
	void existingConstructorCreatesCompletedUser() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		assertThat(user.getProfileSetupStatus()).isEqualTo(ProfileSetupStatus.COMPLETED);
		assertThat(user.isProfileSetupRequired()).isFalse();
	}

	@Test
	@DisplayName("가입 대기 사용자는 프로필 설정 필요 상태로 생성된다")
	void pendingFactoryCreatesRequiredUser() {
		User user = User.pending("user@example.com", null, DEFAULT_NICKNAME, 1L);

		assertThat(user.getProfileSetupStatus()).isEqualTo(ProfileSetupStatus.REQUIRED);
		assertThat(user.isProfileSetupRequired()).isTrue();
		assertThat(user.getPassword()).isNull();
	}

	@Test
	@DisplayName("가입 대기 사용자는 초기 기본 캐릭터를 최종 선택해도 프로필을 완료할 수 있다")
	void completesProfileWithSameDefaultCharacter() {
		User user = User.pending("user@example.com", "password", DEFAULT_NICKNAME, 1L);

		user.completeProfile("final-nickname", 1L);

		assertThat(user.getNickname()).isEqualTo("final-nickname");
		assertThat(user.getCharacterId()).isEqualTo(1L);
		assertThat(user.getProfileSetupStatus()).isEqualTo(ProfileSetupStatus.COMPLETED);
	}

	@Test
	@DisplayName("이미 완료된 사용자는 동일 값으로 완료 재요청할 수 있다")
	void completedUserAcceptsIdempotentCompletion() {
		User user = new User("user@example.com", "password", DEFAULT_NICKNAME, 1L);

		user.completeProfile(DEFAULT_NICKNAME, 1L);

		assertThat(user.getNickname()).isEqualTo(DEFAULT_NICKNAME);
		assertThat(user.getCharacterId()).isEqualTo(1L);
		assertThat(user.getProfileSetupStatus()).isEqualTo(ProfileSetupStatus.COMPLETED);
	}

	@Test
	@DisplayName("이미 완료된 사용자는 완료 재요청으로 닉네임을 바꿀 수 없다")
	void completedUserRejectsCompletionWithDifferentNickname() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		assertThatThrownBy(() -> user.completeProfile("different", 1L))
				.isInstanceOf(IllegalStateException.class);
		assertThat(user.getNickname()).isEqualTo("nickname");
		assertThat(user.getCharacterId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("이미 완료된 사용자는 완료 재요청으로 캐릭터를 바꿀 수 없다")
	void completedUserRejectsCompletionWithDifferentCharacter() {
		User user = new User("user@example.com", "password", "nickname", 1L);

		assertThatThrownBy(() -> user.completeProfile("nickname", 2L))
				.isInstanceOf(IllegalStateException.class);
		assertThat(user.getNickname()).isEqualTo("nickname");
		assertThat(user.getCharacterId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("기본 닉네임을 유지하고 프로필을 완료할 수 있다")
	void completesProfileWithDefaultNickname() {
		User user = User.pending("user@example.com", "password", DEFAULT_NICKNAME, 1L);

		user.completeProfile(DEFAULT_NICKNAME, 2L);

		assertThat(user.isProfileSetupRequired()).isFalse();
		assertThat(user.getNickname()).isEqualTo(DEFAULT_NICKNAME);
		assertThat(user.getCharacterId()).isEqualTo(2L);
	}

	@Test
	@DisplayName("탈퇴 사용자는 프로필을 완료할 수 없다")
	void withdrawnUserCannotCompleteProfile() {
		User user = User.pending("user@example.com", "password", DEFAULT_NICKNAME, 1L);
		user.withdraw();

		assertThatThrownBy(() -> user.completeProfile("final-nickname", 2L))
				.isInstanceOf(IllegalStateException.class);
		assertThat(user.isProfileSetupRequired()).isTrue();
	}

	@Test
	@DisplayName("일반 프로필 변경은 가입 대기 상태를 완료로 바꾸지 않는다")
	void ordinaryProfileChangesDoNotCompletePendingUser() {
		User user = User.pending("user@example.com", "password", DEFAULT_NICKNAME, 1L);

		user.changeNickname("ordinary-change");
		user.changeCharacter(2L);

		assertThat(user.isProfileSetupRequired()).isTrue();
	}
}
