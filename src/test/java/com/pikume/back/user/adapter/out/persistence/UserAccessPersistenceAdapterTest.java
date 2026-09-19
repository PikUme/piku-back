package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.dto.UserAccessProfileStatus;
import com.pikume.back.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("UserAccessPersistenceAdapter")
class UserAccessPersistenceAdapterTest {

	@Autowired
	private UserJpaRepository userJpaRepository;

	@Test
	@DisplayName("비밀번호나 아바타 해석 없이 가입 대기 사용자의 접근 상태만 조회한다")
	void queriesOnlyUserAccessState() {
		User pendingUser = userJpaRepository.saveAndFlush(
				User.pending("pending@example.com", null, "가입대기_access", 1L));
		UserAccessPersistenceAdapter adapter = new UserAccessPersistenceAdapter(userJpaRepository);

		var result = adapter.queryUserAccess(pendingUser.getId()).orElseThrow();

		assertThat(result.id()).isEqualTo(pendingUser.getId());
		assertThat(result.withdrawn()).isFalse();
		assertThat(result.profileSetupStatus()).isEqualTo(UserAccessProfileStatus.REQUIRED);
	}

	@Test
	@DisplayName("탈퇴 사용자의 접근 상태에 탈퇴 여부를 포함한다")
	void exposesWithdrawnState() {
		User user = new User("withdrawn@example.com", "password", "withdrawn", 1L);
		user.withdraw();
		userJpaRepository.saveAndFlush(user);
		UserAccessPersistenceAdapter adapter = new UserAccessPersistenceAdapter(userJpaRepository);

		var result = adapter.queryUserAccess(user.getId()).orElseThrow();

		assertThat(result.withdrawn()).isTrue();
		assertThat(result.profileSetupStatus()).isEqualTo(UserAccessProfileStatus.COMPLETED);
	}

	@Test
	@DisplayName("프로필 완료 처리용 사용자 조회는 쓰기 잠금 쿼리로 적재한다")
	void loadsUserWithWriteLock() {
		User user = userJpaRepository.saveAndFlush(
				new User("locked@example.com", "password", "locked", 1L));

		assertThat(userJpaRepository.findByIdForUpdate(user.getId())).contains(user);
	}
}
