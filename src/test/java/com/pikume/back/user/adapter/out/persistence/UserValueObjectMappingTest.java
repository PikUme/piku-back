package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.domain.User;
import com.pikume.back.global.pagination.PageQuery;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import com.pikume.back.user.domain.vo.Nickname;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@DisplayName("User value object persistence mapping")
class UserValueObjectMappingTest {

	@Autowired
	private UserJpaRepository userJpaRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("값 객체와 캐릭터 식별자를 users 컬럼에 저장하고 복원한다")
	void persistsValueObjectsAndCharacterIdentifier() {
		User saved = userJpaRepository.saveAndFlush(new User(
				"user@example.com",
				"password-hash",
				" \u2003pikume\u3000 ",
				1L));
		entityManager.clear();

		User restored = userJpaRepository.findById(saved.getId()).orElseThrow();
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT email, nickname, character_id FROM users WHERE id = ?",
				saved.getId());

		assertThat(restored.getEmail()).isEqualTo("user@example.com");
		assertThat(restored.getNickname()).isEqualTo("pikume");
		assertThat(restored.getCharacterId()).isEqualTo(1L);
		assertThat(row.get("email")).isEqualTo("user@example.com");
		assertThat(row.get("nickname")).isEqualTo("pikume");
		assertThat(((Number) row.get("character_id")).longValue()).isEqualTo(1L);
	}

	@Test
	@DisplayName("기존 문자열 Repository 계약으로 값 객체 컬럼을 조회한다")
	void queriesValueObjectColumnsUsingStringContracts() {
		userJpaRepository.saveAndFlush(new User(
				"user@example.com",
				"password-hash",
				"pikume-user",
				1L));
		entityManager.clear();

		UserAccountPersistenceAdapter accountAdapter = new UserAccountPersistenceAdapter(userJpaRepository);
		UserSearchPersistenceAdapter searchAdapter = new UserSearchPersistenceAdapter(userJpaRepository);

		assertThat(accountAdapter.loadForLogin("user@example.com")).isPresent();
		assertThat(accountAdapter.isEmailRegistered("user@example.com")).isTrue();
		assertThat(accountAdapter.isNicknameInUse(new Nickname(" \u2003pikume-user\u3000 "))).isTrue();
		assertThat(searchAdapter.searchUsers("%pikume%", PageQuery.of(0, 20)).getContent())
				.singleElement()
				.satisfies(user -> assertThat(user.getNickname()).isEqualTo("pikume-user"));
	}

	@Test
	@DisplayName("실제 nickname 유일 제약 위반을 User 충돌 의미로 번역한다")
	void translatesNicknameConstraintViolationAtPersistenceBoundary() {
		userJpaRepository.saveAndFlush(new User(
				"first@example.com", "password", " \u2003duplicate-nickname\u3000 ", 1L));
		User duplicate = new User(
				"second@example.com", "password", "duplicate-nickname", 1L);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(duplicate))
				.isInstanceOf(NicknameAlreadyExistsException.class);
	}

	@Test
	@DisplayName("실제 email 유일 제약 위반을 User 계정 충돌 의미로 번역한다")
	void translatesEmailConstraintViolationAtPersistenceBoundary() {
		userJpaRepository.saveAndFlush(new User(
				"duplicate@example.com", "password", "first-nickname", 1L));
		User duplicate = new User(
				"duplicate@example.com", "password", "second-nickname", 1L);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(duplicate))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}
}
