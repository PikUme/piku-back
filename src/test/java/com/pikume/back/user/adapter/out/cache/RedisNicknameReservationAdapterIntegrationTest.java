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
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
	void concurrentReservationsForAnEmptyNicknameReturnConflictToOneOwner() throws Exception {
		CyclicBarrier scriptBarrier = new CyclicBarrier(2);
		StringRedisTemplate gatedTemplate = new GatedRedisTemplate(connection, scriptBarrier);
		gatedTemplate.afterPropertiesSet();
		NicknameReservationStorePort concurrentStore = new RedisNicknameReservationAdapter(gatedTemplate);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(() -> reserveOrReturnFailure(concurrentStore, "signup:owner-a"));
			Future<Object> second = executor.submit(() -> reserveOrReturnFailure(concurrentStore, "signup:owner-b"));

			List<Object> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

			assertThat(outcomes).extracting(Object::getClass).containsExactlyInAnyOrder(
					NicknameReservationResult.class, NicknameReservationConflictException.class);
			assertThat(outcomes).filteredOn(NicknameReservationResult.class::isInstance).hasSize(1);
			assertThat(outcomes).filteredOn(NicknameReservationConflictException.class::isInstance).hasSize(1);
			assertThat(outcomes).noneMatch(NicknameReservationUnavailableException.class::isInstance);
		} finally {
			executor.shutdownNow();
			gatedTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		}
	}

	@Test
	void concurrentSameOwnerSameNicknameReservationsRemainIdempotent() throws Exception {
		CyclicBarrier scriptBarrier = new CyclicBarrier(2);
		StringRedisTemplate gatedTemplate = new GatedRedisTemplate(connection, scriptBarrier);
		gatedTemplate.afterPropertiesSet();
		NicknameReservationStorePort concurrentStore = new RedisNicknameReservationAdapter(gatedTemplate);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(() -> reserveOrReturnFailure(concurrentStore, "user:owner-a", "identity-1"));
			Future<Object> second = executor.submit(() -> reserveOrReturnFailure(concurrentStore, "user:owner-a", "identity-1"));
			List<Object> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

			assertThat(outcomes).allMatch(NicknameReservationResult.class::isInstance);
			NicknameReservationResult firstHold = (NicknameReservationResult) outcomes.get(0);
			NicknameReservationResult secondHold = (NicknameReservationResult) outcomes.get(1);
			assertThat(secondHold.version()).isEqualTo(firstHold.version());
			assertThat(secondHold.expiresAt()).isEqualTo(firstHold.expiresAt());
			assertThat(concurrentStore.load("user:owner-a")).contains(firstHold);
		} finally {
			executor.shutdownNow();
			gatedTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		}
	}

	@Test
	void concurrentDifferentNameReplacementReturnsConflictAndPreservesWinningReservation() throws Exception {
		String owner = "user:owner-a";
		NicknameReservationResult initial = store.reserve(owner, "identity-0", "Initial");
		CyclicBarrier scriptBarrier = new CyclicBarrier(2);
		StringRedisTemplate gatedTemplate = new GatedRedisTemplate(connection, scriptBarrier);
		gatedTemplate.afterPropertiesSet();
		NicknameReservationStorePort concurrentStore = new RedisNicknameReservationAdapter(gatedTemplate);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(() -> reserveOrReturnFailure(concurrentStore, owner, "identity-1"));
			Future<Object> second = executor.submit(() -> reserveOrReturnFailure(concurrentStore, owner, "identity-2"));
			List<Object> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

			assertThat(outcomes).extracting(Object::getClass).containsExactlyInAnyOrder(
					NicknameReservationResult.class, NicknameReservationConflictException.class);
			NicknameReservationResult winner = (NicknameReservationResult) outcomes.stream()
					.filter(NicknameReservationResult.class::isInstance).findFirst().orElseThrow();
			assertThat(winner.nicknameKey()).isIn("identity-1", "identity-2");
			assertThat(winner.version()).isNotEqualTo(initial.version());
			assertThat(concurrentStore.load(owner)).contains(winner);
			assertThat(concurrentStore.isHeldBy("identity-0", owner)).isFalse();
			assertThat(concurrentStore.isHeldBy(winner.nicknameKey(), owner)).isTrue();
			assertThat(outcomes).noneMatch(NicknameReservationUnavailableException.class::isInstance);
		} finally {
			executor.shutdownNow();
			gatedTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		}
	}

	@Test
	void sameOwnerSameNameRequestUsesCurrentTargetAfterInterveningOwnerChanges() {
		String ownerA = "user:owner-a";
		String ownerB = "user:owner-b";
		store.reserve(ownerB, "identity-target", "Target");
		NicknameReservationResult[] latestA = new NicknameReservationResult[1];
		NicknameReservationResult[] latestB = new NicknameReservationResult[1];
		StringRedisTemplate delayed = new BeforeFirstScriptRedisTemplate(connection, () -> {
			latestB[0] = store.reserve(ownerB, "identity-other", "Other");
			latestA[0] = store.reserve(ownerA, "identity-target", "Target");
		});
		delayed.afterPropertiesSet();
		NicknameReservationStorePort delayedStore = new RedisNicknameReservationAdapter(delayed);

		NicknameReservationResult reservation = delayedStore.reserve(ownerA, "identity-target", "Target");

		assertThat(reservation).isEqualTo(latestA[0]);
		assertThat(store.load(ownerA)).contains(latestA[0]);
		assertThat(store.load(ownerB)).contains(latestB[0]);
	}

	@Test
	void absentOwnerSnapshotIsDistinctFromValidEmptyNicknameKeyDuringConcurrentReserve() {
		String owner = "user:owner-a";
		NicknameReservationResult[] emptyHold = new NicknameReservationResult[1];
		StringRedisTemplate delayed = new BeforeFirstScriptRedisTemplate(connection, () ->
				emptyHold[0] = store.reserve(owner, "", "Empty weight key"));
		delayed.afterPropertiesSet();
		NicknameReservationStorePort delayedStore = new RedisNicknameReservationAdapter(delayed);

		assertThatThrownBy(() -> delayedStore.reserve(owner, "identity-next", "Next"))
				.isInstanceOf(NicknameReservationConflictException.class)
				.isNotInstanceOf(NicknameReservationUnavailableException.class);
		assertThat(store.load(owner)).contains(emptyHold[0]);
		assertThat(store.isHeldBy("", owner)).isTrue();
	}

	@Test
	void loadUsesSingleOwnerSnapshotWhenReservationChangesImmediatelyAfterRead() {
		String owner = "user:owner-a";
		NicknameReservationResult original = store.reserve(owner, "identity-0", "Initial");
		AtomicInteger reads = new AtomicInteger();
		NicknameReservationResult[] replacement = new NicknameReservationResult[1];
		StringRedisTemplate counting = new HashReadCountingTemplate(connection, reads, () ->
				replacement[0] = store.reserve(owner, "identity-1", "Replacement"));
		counting.afterPropertiesSet();
		NicknameReservationStorePort reader = new RedisNicknameReservationAdapter(counting);

		assertThat(reader.load(owner)).contains(original);
		assertThat(reads).hasValue(1);
		assertThat(store.load(owner)).contains(replacement[0]);
	}

	@Test
	void nicknameChecksUseOneNicknameSnapshotAndDoNotFailOnConcurrentReplacement() {
		String owner = "user:owner-a";
		store.reserve(owner, "identity-0", "Initial");
		AtomicInteger reads = new AtomicInteger();
		NicknameReservationResult[] replacement = new NicknameReservationResult[1];
		StringRedisTemplate counting = new HashReadCountingTemplate(connection, reads, () ->
				replacement[0] = store.reserve(owner, "identity-1", "Replacement"));
		counting.afterPropertiesSet();
		NicknameReservationStorePort reader = new RedisNicknameReservationAdapter(counting);

		assertThat(reader.isHeldBy("identity-0", owner)).isTrue();
		assertThat(reads).hasValue(1);
		assertThat(store.load(owner)).contains(replacement[0]);
		assertThat(store.isHeldBy("identity-1", owner)).isTrue();
	}

	private static Object reserveOrReturnFailure(NicknameReservationStorePort store, String owner) {
		try {
			return store.reserve(owner, "identity-race", "Same name");
		} catch (RuntimeException exception) {
			return exception;
		}
	}

	private static Object reserveOrReturnFailure(NicknameReservationStorePort store, String owner, String nicknameKey) {
		try {
			return store.reserve(owner, nicknameKey, nicknameKey);
		} catch (RuntimeException exception) {
			return exception;
		}
	}

	private static final class HashReadCountingTemplate extends StringRedisTemplate {
		private final AtomicInteger reads;
		private final Runnable afterRead;
		private final java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();

		private HashReadCountingTemplate(LettuceConnectionFactory connection, AtomicInteger reads, Runnable afterRead) {
			super(connection);
			this.reads = reads;
			this.afterRead = afterRead;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <HK, HV> org.springframework.data.redis.core.HashOperations<String, HK, HV> opsForHash() {
			var delegate = super.opsForHash();
			return (org.springframework.data.redis.core.HashOperations<String, HK, HV>) java.lang.reflect.Proxy
					.newProxyInstance(delegate.getClass().getClassLoader(),
						new Class<?>[] { org.springframework.data.redis.core.HashOperations.class }, (proxy, method, args) -> {
							Object result = method.invoke(delegate, args);
							if ("entries".equals(method.getName())) {
								reads.incrementAndGet();
								if (changed.compareAndSet(false, true)) afterRead.run();
							}
							return result;
						});
		}
	}

	private static final class BeforeFirstScriptRedisTemplate extends StringRedisTemplate {
		private final Runnable beforeFirstScript;
		private final java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();

		private BeforeFirstScriptRedisTemplate(LettuceConnectionFactory connection, Runnable beforeFirstScript) {
			super(connection);
			this.beforeFirstScript = beforeFirstScript;
		}

		@Override
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			if (invoked.compareAndSet(false, true)) beforeFirstScript.run();
			return super.execute(script, keys, args);
		}
	}

	private static final class GatedRedisTemplate extends StringRedisTemplate {
		private final CyclicBarrier scriptBarrier;
		private final AtomicInteger executions = new AtomicInteger();

		private GatedRedisTemplate(LettuceConnectionFactory connection, CyclicBarrier scriptBarrier) {
			super(connection);
			this.scriptBarrier = scriptBarrier;
		}

		@Override
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			if (executions.getAndIncrement() < 2) {
				try {
					scriptBarrier.await(5, TimeUnit.SECONDS);
				} catch (Exception exception) {
					throw new IllegalStateException("Concurrent reservation test did not reach the Redis script together", exception);
				}
			}
			return super.execute(script, keys, args);
		}
	}


	@Test
	void ownerSnapshotRemainsReadableWhenNicknameIndexIsMissing() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.delete("nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1");

		assertThat(store.load("user:owner-a")).isPresent();
	}

	@Test
	void nicknameSnapshotRemainsReadableWhenOwnerIndexIsMissing() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.delete("nickname-reservations:v1:{nickname-holds-v1}:owner:user:owner-a");

		assertThat(store.isHeldBy("identity-1", "user:owner-a")).isTrue();
	}

	@Test
	void ownerSnapshotIsReadableButMutationStillDetectsMismatchedPairVersions() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().put("nickname-reservations:v1:{nickname-holds-v1}:owner:user:owner-a", "version", "other-version");

		assertThat(store.load("user:owner-a")).isPresent();
		assertThatThrownBy(() -> store.releaseIfVersionMatches("user:owner-a", "identity-1",
				store.load("user:owner-a").orElseThrow().version()))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void reserveRejectsTargetWithMissingOwnerWithoutOverwritingOrExtendingItsTtl() {
		NicknameReservationResult existing = store.reserve("user:owner-a", "identity-1", "Name");
		String targetKey = "nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1";
		template.opsForHash().delete(targetKey, "ownerKey");
		Long ttlBefore = template.getExpire(targetKey, java.util.concurrent.TimeUnit.MILLISECONDS);

		assertThatThrownBy(() -> store.reserve("user:owner-b", "identity-1", "Name"))
				.isInstanceOf(NicknameReservationUnavailableException.class);

		assertThat(template.opsForHash().entries(targetKey)).doesNotContainKey("ownerKey");
		assertThat(template.getExpire(targetKey, java.util.concurrent.TimeUnit.MILLISECONDS))
				.isLessThanOrEqualTo(ttlBefore)
				.isGreaterThan(ttlBefore - 5_000);
		assertThat(store.load("user:owner-a")).contains(existing);
	}

	@Test
	void reserveRejectsMalformedTargetRecordBeforeChangingCurrentHold() {
		NicknameReservationResult existing = store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().delete("nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1", "version");

		assertThatThrownBy(() -> store.reserve("user:owner-b", "identity-1", "Name"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
		assertThat(store.load("user:owner-a")).contains(existing);
	}

	@Test
	void malformedOwnerSnapshotIsUnavailable() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().delete("nickname-reservations:v1:{nickname-holds-v1}:owner:user:owner-a", "expiresAt");

		assertThatThrownBy(() -> store.load("user:owner-a"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void malformedNicknameSnapshotIsUnavailable() {
		store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().delete("nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1", "version");

		assertThatThrownBy(() -> store.isReservedByOther("identity-1", "user:owner-b"))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void nicknameIndexPointingAtAnotherOwnerIsReadAsItsCurrentSnapshotAndCannotBeReleasedByFirstOwner() {
		NicknameReservationResult reservation = store.reserve("user:owner-a", "identity-1", "Name");
		template.opsForHash().put("nickname-reservations:v1:{nickname-holds-v1}:nickname:identity-1", "ownerKey", "user:owner-b");

		assertThat(store.load("user:owner-a")).contains(reservation);
		assertThat(store.isHeldBy("identity-1", "user:owner-a")).isFalse();
		assertThatThrownBy(() -> store.releaseIfVersionMatches("user:owner-a", "identity-1", reservation.version()))
				.isInstanceOf(NicknameReservationUnavailableException.class);
	}

	@Test
	void commonExpiryMakesBothSingleRecordSnapshotsAbsent() throws InterruptedException {
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
