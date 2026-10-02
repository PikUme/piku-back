package com.pikume.back.user.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pikume.back.user.adapter.out.persistence.UserJpaRepository;
import com.pikume.back.user.adapter.out.persistence.UserPersistenceAdapter;
import com.pikume.back.user.auth.application.port.out.SignUpTransactionPort;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import com.pikume.back.user.domain.vo.Email;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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
	void duplicateEmailRollsBackTheSecondSignupAndPreservesUniqueConstraint() {
		transactions.register(user("duplicate@example.com", "first-nickname"));

		assertThatThrownBy(() -> transactions.register(user("duplicate@example.com", "second-nickname")))
				.isInstanceOf(EmailAlreadyExistsException.class);
		assertThat(users.count()).isEqualTo(1);
		assertThat(users.findByEmail(new Email("duplicate@example.com")).orElseThrow().getNickname())
				.isEqualTo("first-nickname");
	}

	@Test
	void duplicateEmailContainingOtherKnownIndexNameIsStillClassifiedAsEmailConflict() {
		String email = "uk2ty1xmrrgtn89xt7kyxx6ta7h@gmail.com";
		transactions.register(user(email, "ordinary-nickname"));

		assertThatThrownBy(() -> transactions.register(user(email, "another-nickname")))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}

	@Test
	void concurrentSignupWithTheSameEmailCreatesOneMember() throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		try {
			Callable<String> first = () -> createConcurrently("concurrent@example.com", "concurrent-one");
			Callable<String> second = () -> createConcurrently("concurrent@example.com", "concurrent-two");
			var results = executor.invokeAll(List.of(first, second));
			long created = results.stream().filter(result -> {
				try { return result.get().equals("created"); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).count();

			assertThat(created).isEqualTo(1);
			assertThat(users.count()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	private String createConcurrently(String email, String nickname) {
		try {
			transactions.register(user(email, nickname));
			return "created";
		} catch (EmailAlreadyExistsException exception) {
			return "duplicate";
		}
	}

	private static User user(String email, String nickname) {
		return new User(email, "password-hash", nickname, 1L);
	}
}
