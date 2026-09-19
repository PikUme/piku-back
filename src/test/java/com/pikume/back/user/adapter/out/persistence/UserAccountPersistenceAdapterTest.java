package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAccountPersistenceAdapter")
class UserAccountPersistenceAdapterTest {

	@Mock
	private UserJpaRepository userJpaRepository;

	@Test
	@DisplayName("프로필 사용자 적재를 사용자 식별자 조회로 번역한다")
	void loadsProfileUserByUserId() {
		User user = user("user-1", "user@example.com");
		given(userJpaRepository.findById("user-1")).willReturn(Optional.of(user));
		UserAccountPersistenceAdapter adapter = new UserAccountPersistenceAdapter(userJpaRepository);

		assertThat(adapter.loadProfileUser("user-1")).contains(user);

		then(userJpaRepository).should().findById("user-1");
	}

	@Test
	@DisplayName("로그인 사용자 적재를 이메일 값 객체 조회로 번역한다")
	void loadsLoginUserByEmail() {
		User user = user("user-1", "user@example.com");
		Email email = new Email("user@example.com");
		given(userJpaRepository.findByEmail(email)).willReturn(Optional.of(user));
		UserAccountPersistenceAdapter adapter = new UserAccountPersistenceAdapter(userJpaRepository);

		assertThat(adapter.loadForLogin("user@example.com")).contains(user);

		then(userJpaRepository).should().findByEmail(email);
	}

	@Test
	@DisplayName("비밀번호 재설정 사용자 적재를 이메일 값 객체 조회로 번역한다")
	void loadsPasswordResetUserByEmail() {
		User user = user("user-1", "user@example.com");
		Email email = new Email("user@example.com");
		given(userJpaRepository.findByEmail(email)).willReturn(Optional.of(user));
		UserAccountPersistenceAdapter adapter = new UserAccountPersistenceAdapter(userJpaRepository);

		assertThat(adapter.loadPasswordResetUser("user@example.com")).contains(user);

		then(userJpaRepository).should().findByEmail(email);
	}

	@Test
	@DisplayName("닉네임 사용 여부 조회에 정규화된 값 객체를 전달한다")
	void checksNicknameUsageWithNormalizedValueObject() {
		Nickname nickname = new Nickname(" \u2003pikume\u3000 ");
		given(userJpaRepository.existsByNickname(nickname)).willReturn(true);
		UserAccountPersistenceAdapter adapter = new UserAccountPersistenceAdapter(userJpaRepository);

		assertThat(adapter.isNicknameInUse(nickname)).isTrue();

		then(userJpaRepository).should().existsByNickname(new Nickname("pikume"));
	}

	@Test
	@DisplayName("복수 사용자 참조 적재를 식별자 집합 조회로 번역한다")
	void loadsUserReferencesByUserIds() {
		List<String> userIds = List.of("user-1", "user-2");
		List<User> users = List.of(
				user("user-1", "one@example.com"),
				user("user-2", "two@example.com"));
		given(userJpaRepository.findAllById(userIds)).willReturn(users);
		UserAccountPersistenceAdapter adapter = new UserAccountPersistenceAdapter(userJpaRepository);

		assertThat(adapter.loadReferences(userIds)).containsExactlyElementsOf(users);

		then(userJpaRepository).should().findAllById(userIds);
	}

	private User user(String userId, String email) {
		return new User(userId, email, "password", "nickname", 1L);
	}
}
