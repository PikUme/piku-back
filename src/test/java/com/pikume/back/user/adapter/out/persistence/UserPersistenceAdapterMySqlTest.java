package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("UserPersistenceAdapter with MySQL")
class UserPersistenceAdapterMySqlTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
			.withDatabaseName("pikume")
			.withUsername("pikume")
			.withPassword("pikume");

	@DynamicPropertySource
	static void configureDatasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
		registry.add("spring.flyway.enabled", () -> false);
	}

	@Autowired
	private UserJpaRepository userJpaRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("email 중복 저장은 기존 signup 저장 포트에서 계정 충돌로 변환하고 새 row를 롤백한다")
	void translatesEmailDuplicateAndRollsBackSignupInsert() {
		User existing = new User("duplicate@example.com", "password", "existing-nickname", 1L);
		persist(existing);
		User duplicate = new User("duplicate@example.com", "password", "new-nickname", 1L);

		assertThatThrownBy(() -> transaction().executeWithoutResult(status ->
				new UserPersistenceAdapter(userJpaRepository).recordUserAccount(duplicate)))
				.isInstanceOf(EmailAlreadyExistsException.class);

		assertThat(countBy("nickname", "new-nickname")).isZero();
	}

	@Test
	@DisplayName("nickname 중복 저장은 기존 signup 저장 포트에서 닉네임 충돌로 변환하고 새 row를 롤백한다")
	void translatesNicknameDuplicateAndRollsBackSignupInsert() {
		User existing = new User("existing@example.com", "password", "duplicate-nickname", 1L);
		persist(existing);
		User duplicate = new User("new@example.com", "password", "duplicate-nickname", 1L);

		assertThatThrownBy(() -> transaction().executeWithoutResult(status ->
				new UserPersistenceAdapter(userJpaRepository).recordUserAccount(duplicate)))
				.isInstanceOf(NicknameAlreadyExistsException.class);

		assertThat(countBy("email", "new@example.com")).isZero();
	}

	private long countBy(String column, String value) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE " + column + " = ?", Long.class, value);
	}

	private TransactionTemplate transaction() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transaction;
	}

	private void persist(User user) {
		transaction().executeWithoutResult(status -> userJpaRepository.saveAndFlush(user));
	}
}
