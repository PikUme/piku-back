package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.domain.vo.Nickname;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("mysql-migration")
class NicknameHoldMigrationTest {
	@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("pikume").withUsername("pikume").withPassword("pikume");
	private JdbcTemplate jdbc;
	private NicknameHoldPersistenceAdapter holds;
	private TransactionTemplate tx;

	@BeforeEach void migrate() {
		var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		var flyway = Flyway.configure().dataSource(source).cleanDisabled(false).target("18").load();
		flyway.clean();
		flyway.migrate();
		jdbc = new JdbcTemplate(source);
		holds = new NicknameHoldPersistenceAdapter(jdbc);
		tx = new TransactionTemplate(new DataSourceTransactionManager(source));
	}

	@Test void reservationsFollowUsersAccentAndCaseInsensitiveCollationWithoutRenewal() {
		String userCollation = jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'nickname'", String.class);
		String holdCollation = jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nickname_holds' AND COLUMN_NAME = 'nickname'", String.class);
		assertThat(holdCollation).isEqualTo(userCollation).isEqualTo("utf8mb4_0900_ai_ci");
		Instant now = Instant.parse("2026-09-07T00:00:00Z");
		tx.executeWithoutResult(status -> {
			holds.lockNicknameWrites();
			assertThat(holds.tryAcquire(new Nickname("Résumé"), "user-1", now)).isTrue();
			assertThat(holds.tryAcquire(new Nickname("RESUME"), "user-1", now.plusSeconds(60))).isTrue();
			assertThat(holds.heldUntil(new Nickname("resume"), "user-1", now.plusSeconds(60))).contains(now.plusSeconds(180));
			assertThat(holds.tryAcquire(new Nickname("resume"), "user-2", now.plusSeconds(60))).isFalse();
		});
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_write_mutex", Integer.class)).isEqualTo(1);
	}

	@Test void collationEquivalentConcurrentRequestsHaveSingleOwner() throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		Instant now = Instant.now();
		try {
			List<String> names = List.of("Résumé", "RESUME");
			List<Future<Boolean>> results = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
				String nickname = names.get(index);
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
				return tx.execute(status -> { holds.lockNicknameWrites(); return holds.tryAcquire(new Nickname("  " + nickname + "　 "), "user-" + index, now); });
			})).toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int successes = 0;
			for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) successes++;
			assertThat(successes).isEqualTo(1);
		} finally { executor.shutdownNow(); }
	}
}
