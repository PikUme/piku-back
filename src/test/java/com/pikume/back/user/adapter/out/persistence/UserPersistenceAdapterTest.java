package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPersistenceAdapter")
class UserPersistenceAdapterTest {

	@Mock
	private UserJpaRepository userJpaRepository;

	@Test
	@DisplayName("nickname 유일 제약 위반을 도메인 충돌 의미로 번역한다")
	void translatesNicknameConstraintViolation() {
		User user = new User("user@example.com", "password", "duplicate-nickname", 1L);
		given(userJpaRepository.saveAndFlush(user)).willThrow(uniqueConstraintFailure(
				"UK2ty1xmrrgtn89xt7kyxx6ta7h"));

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isInstanceOf(NicknameAlreadyExistsException.class)
				.hasMessageContaining("duplicate-nickname");
	}

	@Test
	@DisplayName("email 유일 제약 위반을 계정 충돌 의미로 번역한다")
	void translatesEmailConstraintViolation() {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = uniqueConstraintFailure(
				"UK6dotkott2kjsp8vw4d0m25fb7");
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"uk_users_unknown", "users(email)", "users(nickname)"})
	@DisplayName("알 수 없는 유일 제약 위반은 저장 기술 예외를 임의로 번역하지 않는다")
	void preservesUnknownConstraintViolation(String constraintName) {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = uniqueConstraintFailure(constraintName);
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isSameAs(failure);
	}

	@Test
	@DisplayName("MySQL 1062 오류의 키 이름으로 이메일 제약을 판별한다")
	void translatesMysqlDuplicateEmailConstraint() {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = mysqlDuplicateKeyFailure(
				"Duplicate entry 'user@example.com' for key 'pikume.UK6dotkott2kjsp8vw4d0m25fb7'");
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}

	@Test
	@DisplayName("MySQL 1062 오류의 키 이름으로 닉네임 제약을 판별한다")
	void translatesMysqlDuplicateNicknameConstraint() {
		User user = new User("user@example.com", "password", "duplicate-nickname", 1L);
		DataIntegrityViolationException failure = mysqlDuplicateKeyFailure(
				"Duplicate entry 'duplicate-nickname' for key 'users.UK2ty1xmrrgtn89xt7kyxx6ta7h'");
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isInstanceOf(NicknameAlreadyExistsException.class)
				.hasMessageContaining("duplicate-nickname");
	}

	@Test
	@DisplayName("MySQL 1062 오류의 중복 값에 인덱스 이름이 있어도 알 수 없는 키를 번역하지 않는다")
	void preservesUnknownMysqlDuplicateKeyWhenValueContainsKnownIndexName() {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = mysqlDuplicateKeyFailure(
				"Duplicate entry 'UK6dotkott2kjsp8vw4d0m25fb7' for key 'users.uk_users_external_id'");
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isSameAs(failure);
	}

	@Test
	@DisplayName("중복 값 안의 for key 구절을 실제 MySQL 키 이름으로 오인하지 않는다")
	void preservesUnknownMysqlDuplicateKeyWhenValueContainsKeyPhrase() {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = mysqlDuplicateKeyFailure(
				"Duplicate entry 'value for key 'UK6dotkott2kjsp8vw4d0m25fb7' tail' "
						+ "for key 'users.uk_users_external_id'");
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isSameAs(failure);
	}

	@Test
	@DisplayName("MySQL 1062 오류의 키가 없으면 알 수 없는 무결성 오류를 번역하지 않는다")
	void preservesMysqlDuplicateKeyWithoutKnownKeyName() {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = mysqlDuplicateKeyFailure("Duplicate entry 'user@example.com'");
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isSameAs(failure);
	}

	@Test
	@DisplayName("MySQL 예외 메시지가 없으면 알 수 없는 무결성 오류를 번역하지 않는다")
	void preservesMysqlDuplicateKeyWithoutMessage() {
		User user = new User("user@example.com", "password", "nickname", 1L);
		DataIntegrityViolationException failure = mysqlDuplicateKeyFailure(null);
		given(userJpaRepository.saveAndFlush(user)).willThrow(failure);

		assertThatThrownBy(() -> new UserPersistenceAdapter(userJpaRepository).recordUserAccount(user))
				.isSameAs(failure);
	}

	private DataIntegrityViolationException uniqueConstraintFailure(String constraintName) {
		SQLException sqlException = new SQLException("duplicate key", "23505");
		ConstraintViolationException constraintViolation = new ConstraintViolationException(
				"could not execute statement",
				sqlException,
				"insert into users (email, nickname) values (?, ?)",
				constraintName);
		return new DataIntegrityViolationException(
				"could not execute insert into users (email, nickname)",
				constraintViolation);
	}

	private DataIntegrityViolationException mysqlDuplicateKeyFailure(String message) {
		SQLException sqlException = new SQLException(message, "23000", 1062);
		ConstraintViolationException constraintViolation = new ConstraintViolationException(
				"could not execute statement",
				sqlException,
				"insert into users (email, nickname) values (?, ?)",
				null);
		return new DataIntegrityViolationException("could not execute insert", constraintViolation);
	}
}
