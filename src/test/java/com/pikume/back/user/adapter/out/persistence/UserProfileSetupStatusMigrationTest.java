package com.pikume.back.user.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("mysql-migration")
@DisplayName("V16 사용자 프로필 설정 상태 마이그레이션")
class UserProfileSetupStatusMigrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
			.withDatabaseName("pikume")
			.withUsername("pikume")
			.withPassword("pikume");

	private DataSource dataSource;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void migrateToVersion15() {
		dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
		Flyway flyway = Flyway.configure()
				.dataSource(dataSource)
				.cleanDisabled(false)
				.target(MigrationVersion.fromVersion("15"))
				.load();
		flyway.clean();
		flyway.migrate();
	}

	@Test
	@DisplayName("기존 회원을 완료 상태로 이관하고 탈퇴 시각과 nullable 비밀번호를 보존한다")
	void migratesExistingUsersAsCompletedWithoutChangingExistingColumns() {
		jdbcTemplate.update("""
				INSERT INTO characters (image_url, type) VALUES ('fixed.webp', 'FIXED')
				""");
		Long characterId = jdbcTemplate.queryForObject(
				"SELECT id FROM characters WHERE image_url = 'fixed.webp'", Long.class);
		jdbcTemplate.update("""
				INSERT INTO users (id, email, password, nickname, character_id, deleted_at)
				VALUES ('user-1', 'user@example.com', NULL, 'nickname', ?, '2026-01-02 03:04:05')
				""", characterId);

		Flyway.configure().dataSource(dataSource).load().migrate();

		assertThat(jdbcTemplate.queryForObject(
				"SELECT profile_setup_status FROM users WHERE id = 'user-1'", String.class))
				.isEqualTo("COMPLETED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM users WHERE id = 'user-1' AND password IS NULL", Integer.class))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT deleted_at FROM users WHERE id = 'user-1'", String.class))
				.startsWith("2026-01-02 03:04:05");
	}

	@Test
	@DisplayName("구버전 쓰기는 상태를 생략해도 완료 상태가 적용된다")
	void appliesCompletedDefaultToLegacyWrites() {
		Flyway.configure().dataSource(dataSource).load().migrate();
		jdbcTemplate.update("""
				INSERT INTO users (id, email, password, nickname, character_id)
				VALUES ('legacy-user', 'legacy@example.com', 'password', 'legacy', 1)
				""");

		assertThat(jdbcTemplate.queryForObject(
				"SELECT profile_setup_status FROM users WHERE id = 'legacy-user'", String.class))
				.isEqualTo("COMPLETED");
	}
}
