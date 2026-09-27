package com.pikume.back.user.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the same profile/reset race and rollback scenarios with MySQL row locking. */
@Testcontainers
@Tag("mysql-migration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SignupProfileMySqlIntegrationTest extends SignupProfilePersistenceIntegrationTest {
	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

	@DynamicPropertySource
	static void mysqlProperties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
		properties.add("spring.datasource.username", MYSQL::getUsername);
		properties.add("spring.datasource.password", MYSQL::getPassword);
		properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
		properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
	}

	@Override
	@BeforeEach
	void setup() {
		jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_write_mutex (id INT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_holds (nickname VARCHAR(255) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, expires_at DATETIME(6) NOT NULL)");
		jdbc.update("INSERT INTO nickname_write_mutex (id) VALUES (1) ON DUPLICATE KEY UPDATE id = id");
		jdbc.update("DELETE FROM nickname_holds");
		users.deleteAll();
	}
}
