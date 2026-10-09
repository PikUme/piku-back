package com.pikume.back.user.adapter.out.cache;

import com.pikume.back.user.application.dto.NicknameReservationResult;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.application.exception.NicknameReservationUnavailableException;
import com.pikume.back.user.application.port.out.NicknameReservationStorePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisNicknameReservationAdapterIntegrationTest {

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
	private static LettuceConnectionFactory connection;
	private static StringRedisTemplate template;
	private NicknameReservationStorePort store;

	@BeforeAll
	static void connect() {
		connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connection.afterPropertiesSet();
		template = new StringRedisTemplate(connection);
		template.afterPropertiesSet();
	}

	@AfterAll
	static void close() {
		if (connection != null) connection.destroy();
	}

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		store = new RedisNicknameReservationAdapter(template);
	}

	@AfterEach
	void clearRedis() {
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void sameOwnerSameNicknameKeepsOriginalVersionAndExpiry() {
		NicknameReservationResult first = store.reserve("signup:owner-a", "identity-1", "Name");
		NicknameReservationResult second = store.reserve("signup:owner-a", "identity-1", "Name");

		assertThat(second.version()).isEqualTo(first.version());
		assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
		assertThat(store.load("signup:owner-a")).contains(second);
	}

	@Test
	void otherOwnerCannotReserveNicknameAndOriginalReservationRemains() {
		NicknameReservationResult existing = store.reserve("signup:owner-a", "identity-1", "Name");

		assertThatThrownBy(() -> store.reserve("signup:owner-b", "identity-1", "Other case"))
				.isInstanceOf(NicknameReservationConflictException.class);
		assertThat(store.load("signup:owner-a")).contains(existing);
		assertThat(store.isHeldBy("identity-1", "signup:owner-b")).isFalse();
	}

	@Test
	void missingNicknameIndexIsReportedAsUnavailableInsteadOfNoReservation() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.delete("nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1");

		assertThatThrownBy(() -> store.load("user:owner-a"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void missingOwnerIndexIsReportedAsUnavailableInsteadOfUnheld() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.delete("nickname-reservations:v1:{nickname-holds-v1}:owner:user:owner-a");

		assertThatThrownBy(() -> store.isHeldBy("identity-1", "user:owner-a"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void mismatchedPairVersionsAreReportedAsUnavailable() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().put("nickname-reservations:v1:{nickname-holds-v1}:owner:user:owner-a", "version", "other-version");

		assertThatThrownBy(() -> store.load("user:owner-a"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void nicknameIndexPointingAtAnotherOwnerCannotBeReadOrReleasedByFirstOwner() {
		NicknameReservationResult reservation = store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().put("nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1", "ownerKey", "user:owner-b");

		assertThatThrownBy(() -> store.load("user:owner-a"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
		assertThatThrownBy(() -> store.releaseIfVersionMatches("user:owner-a", "identity-1", reservation.version()))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void commonExpiryIsObservedAsMissingPairInsteadOfPartialCorruption() throws InterruptedException {
		store.reserve("user:owner-a", "identity-1", "Name");
		String ownerKey = "nickname-reservations:v1:{nickname-holds-v1}:owner:user:owner-a";
		String nicknameKey = "nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1";
		template.expire(ownerKey, java.time.Duration.ofMillis(20));
		template.expire(nicknameKey, java.time.Duration.ofMillis(20));
		Thread.sleep(60);

		assertThat(store.load("user:owner-a")).isEmpty();
		assertThat(store.isHeldBy("identity-1", "user:owner-a")).isFalse();
	}

	@Test
	void ownerCanReplaceReservationAndStaleVersionCannotReleaseNewHold() {
		NicknameReservationResult old = store.reserve("user:owner-a", "identity-1", "Old");
		NicknameReservationResult replacement = store.reserve("user:owner-a", "identity-2", "New");

		assertThat(store.isHeldBy("identity-1", "user:owner-a")).isFalse();
		assertThat(store.isHeldBy("identity-2", "user:owner-a")).isTrue();
		assertThat(store.releaseIfVersionMatches("user:owner-a", "identity-2", old.version())).isFalse();
		assertThat(store.load("user:owner-a")).contains(replacement);
	}

	@Test
	void staleOwnerReceiptDoesNotDeleteNicknameReownedByAnotherUser() {
		NicknameReservationResult old = store.reserve("user:owner-a", "identity-1", "Old");
		assertThat(store.releaseIfVersionMatches("user:owner-a", "identity-1", old.version())).isTrue();
		NicknameReservationResult newOwner = store.reserve("user:owner-b", "identity-1", "New owner");

		assertThat(store.releaseIfVersionMatches("user:owner-a", "identity-1", old.version())).isFalse();
		assertThat(store.load("user:owner-b")).contains(newOwner);
		assertThat(store.isHeldBy("identity-1", "user:owner-a")).isFalse();
	}
}
