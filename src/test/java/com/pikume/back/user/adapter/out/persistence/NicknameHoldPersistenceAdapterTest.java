package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.domain.vo.Nickname;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class NicknameHoldPersistenceAdapterTest {
	private NicknameHoldPersistenceAdapter holds;
	private JdbcTemplate jdbc;
	private TransactionTemplate tx;
	private DriverManagerDataSource source;
	private final Instant now = Instant.parse("2026-09-07T00:00:00Z");

	@BeforeEach void setup() {
		source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		jdbc = new JdbcTemplate(source);
		tx = new TransactionTemplate(new DataSourceTransactionManager(source));
		jdbc.execute("CREATE TABLE nickname_write_mutex (id INT PRIMARY KEY)");
		jdbc.update("INSERT INTO nickname_write_mutex (id) VALUES (1)");
		jdbc.execute("CREATE TABLE nickname_holds (nickname VARCHAR(255) PRIMARY KEY, user_id VARCHAR(36) NOT NULL UNIQUE, expires_at TIMESTAMP(6) NOT NULL)");
		holds = new NicknameHoldPersistenceAdapter(jdbc);
	}

	@Test void duplicateReservationKeepsFixedExpiryAndExactBoundaryExpires() {
		tx.executeWithoutResult(status -> {
			holds.lockNicknameWrites();
			assertThat(holds.tryAcquire(new Nickname("nick"), "user-1", now)).isTrue();
			assertThat(holds.tryAcquire(new Nickname("nick"), "user-1", now.plusSeconds(60))).isTrue();
			assertThat(holds.heldUntil(new Nickname("nick"), "user-1", now.plusSeconds(60))).contains(now.plusSeconds(180));
			assertThat(holds.isHeldBy(new Nickname("nick"), "user-1", now.plusSeconds(180))).isFalse();
			assertThat(holds.tryAcquire(new Nickname("nick"), "user-2", now.plusSeconds(180))).isTrue();
		});
	}

	@Test void expiryIsStoredAsUtcRegardlessOfApplicationTimezone() {
		source.setUrl(source.getUrl() + ";INIT=SET TIME ZONE 'Pacific/Honolulu'");
		tx.executeWithoutResult(status -> {
			holds.lockNicknameWrites();
			holds.tryAcquire(new Nickname("timezone"), "user-1", now);
		});
		java.time.LocalDateTime stored = jdbc.queryForObject(
			"SELECT expires_at FROM nickname_holds WHERE user_id = 'user-1'", java.time.LocalDateTime.class);
		assertThat(stored).isEqualTo(java.time.LocalDateTime.ofInstant(now.plusSeconds(180), java.time.ZoneOffset.UTC));
	}

	@Test void conflictKeepsPreviousHoldWhileSuccessfulChangeReplacesIt() {
		tx.executeWithoutResult(status -> {
			holds.lockNicknameWrites();
			holds.tryAcquire(new Nickname("first"), "user-1", now);
			holds.tryAcquire(new Nickname("taken"), "user-2", now);
			assertThat(holds.tryAcquire(new Nickname("taken"), "user-1", now.plusSeconds(30))).isFalse();
			assertThat(holds.isHeldBy(new Nickname("first"), "user-1", now.plusSeconds(30))).isTrue();
			assertThat(holds.tryAcquire(new Nickname("replacement"), "user-1", now.plusSeconds(40))).isTrue();
			assertThat(holds.isHeldBy(new Nickname("first"), "user-1", now.plusSeconds(40))).isFalse();
			assertThat(holds.heldUntil(new Nickname("replacement"), "user-1", now.plusSeconds(40))).contains(now.plusSeconds(220));
		});
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds WHERE user_id = 'user-1'", Integer.class)).isEqualTo(1);
	}

	@Test void failedTransactionRestoresReleasedHold() {
		tx.executeWithoutResult(status -> { holds.lockNicknameWrites(); holds.tryAcquire(new Nickname("nick"), "user-1", now); });
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			holds.lockNicknameWrites();
			holds.release(new Nickname("nick"), "user-1");
			throw new IllegalStateException("save failed");
		})).isInstanceOf(IllegalStateException.class);
		Boolean stillHeld = tx.execute(status -> holds.isHeldBy(new Nickname("nick"), "user-1", now));
		assertThat(stillHeld).isTrue();
	}

	@Test void independentConnectionsSerializeAndOnlyOneUserAcquiresSameNickname() throws Exception {
		int count = 8;
		ExecutorService executor = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> results = java.util.stream.IntStream.range(0, count).mapToObj(index -> executor.submit(() -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
				return tx.execute(status -> {
					holds.lockNicknameWrites();
					return holds.tryAcquire(new Nickname("shared"), "user-" + index, now);
				});
			})).toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			int successCount = 0;
			for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successCount++;
			assertThat(successCount).isEqualTo(1);
		} finally { executor.shutdownNow(); }
	}
}
