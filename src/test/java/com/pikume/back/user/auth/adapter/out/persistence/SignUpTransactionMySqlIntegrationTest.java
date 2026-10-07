package com.pikume.back.user.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pikume.back.user.adapter.out.persistence.UserJpaRepository;
import com.pikume.back.user.adapter.out.persistence.UserPersistenceAdapter;
import com.pikume.back.user.auth.application.port.out.SignUpTransactionPort;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import com.pikume.back.user.domain.vo.Email;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Testcontainers
@Import({UserPersistenceAdapter.class, SignUpTransactionAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SignUpTransactionMySqlIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("email_signup_test")
			.withUsername("test")
			.withPassword("test");

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
		properties.add("spring.datasource.username", MYSQL::getUsername);
		properties.add("spring.datasource.password", MYSQL::getPassword);
		properties.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
		properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
	}

	@Autowired
	private SignUpTransactionPort transactions;
	@Autowired
	private UserJpaRepository users;

	@BeforeEach
	void clearUsers() {
		users.deleteAllInBatch();
	}

	@Test
	void registerReturnsOnlyAfterMysqlCommit() {
		User saved = transactions.register(user("commit@example.com", "commit-nickname"));

		assertThat(saved.getId()).isNotBlank();
		assertThat(users.findByEmail(new Email("commit@example.com"))).isPresent();
	}

	@Test
	void databaseFailureRollsBackTheSignupTransaction() {
		transactions.register(user("duplicate@example.com", "first-nickname"));

		assertThatThrownBy(() -> transactions.register(user("duplicate@example.com", "second-nickname")))
				.isInstanceOf(EmailAlreadyExistsException.class);
		assertThat(users.count()).isEqualTo(1);
	}

	private static User user(String email, String nickname) {
		return new User(email, "password-hash", nickname, 1L);
	}
}
