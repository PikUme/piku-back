package com.pikume.back.user.adapter.out.memory;

import com.pikume.back.user.domain.service.NicknamePolicy;
import com.pikume.back.user.domain.vo.Nickname;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryNicknameHoldAdapter")
class InMemoryNicknameHoldAdapterTest {

	private final NicknamePolicy nicknamePolicy = new NicknamePolicy();
	private final InMemoryNicknameHoldAdapter adapter = new InMemoryNicknameHoldAdapter(nicknamePolicy);
	private final Instant requestedAt = Instant.parse("2026-07-12T00:00:00Z");

	@Test
	@DisplayName("동일 사용자는 만료 전 점유를 다시 획득할 수 있다")
	void sameUserCanReacquire() {
		assertThat(adapter.tryAcquire(new Nickname("pikume"), "user-1", requestedAt)).isTrue();

		assertThat(adapter.tryAcquire(new Nickname("pikume"), "user-1", requestedAt.plusSeconds(60))).isTrue();
		assertThat(adapter.isHeldBy(new Nickname("pikume"), "user-1", requestedAt.plusSeconds(179))).isTrue();
	}

	@Test
	@DisplayName("다른 사용자는 만료 전 점유를 획득할 수 없다")
	void otherUserCannotAcquireActiveHold() {
		adapter.tryAcquire(new Nickname("pikume"), "user-1", requestedAt);

		assertThat(adapter.tryAcquire(new Nickname("pikume"), "user-2", requestedAt.plusSeconds(60))).isFalse();
	}

	@Test
	@DisplayName("만료된 점유는 다른 사용자가 교체할 수 있다")
	void expiredHoldCanBeReplaced() {
		adapter.tryAcquire(new Nickname("pikume"), "user-1", requestedAt);

		assertThat(adapter.tryAcquire(new Nickname("pikume"), "user-2", requestedAt.plusSeconds(181))).isTrue();
		assertThat(adapter.isHeldBy(new Nickname("pikume"), "user-2", requestedAt.plusSeconds(181))).isTrue();
	}

	@Test
	@DisplayName("점유 소유자만 점유를 해제할 수 있다")
	void onlyOwnerCanReleaseHold() {
		adapter.tryAcquire(new Nickname("pikume"), "user-1", requestedAt);

		adapter.release(new Nickname("pikume"), "user-2");
		assertThat(adapter.isHeldBy(new Nickname("pikume"), "user-1", requestedAt.plusSeconds(1))).isTrue();

		adapter.release(new Nickname("pikume"), "user-1");
		assertThat(adapter.isHeldBy(new Nickname("pikume"), "user-1", requestedAt.plusSeconds(1))).isFalse();
	}

	@Test
	@DisplayName("공백 형태가 다른 동일 닉네임은 한 사용자만 점유한다")
	void whitespaceVariantsHaveSingleOwner() {
		assertThat(adapter.tryAcquire(new Nickname(" \u2003pikume\u3000 "), "user-1", requestedAt)).isTrue();

		assertThat(adapter.tryAcquire(new Nickname("pikume"), "user-2", requestedAt.plusSeconds(60))).isFalse();
		assertThat(adapter.isHeldBy(new Nickname(" pikume "), "user-1", requestedAt.plusSeconds(60))).isTrue();
	}

	@Test
	@DisplayName("같은 닉네임에 대한 동시 점유 요청 중 한 사용자만 획득한다")
	void concurrentAcquisitionHasSingleOwner() throws Exception {
		int requestCount = 16;
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requestCount);
		try {
			List<Callable<Boolean>> requests = java.util.stream.IntStream.range(0, requestCount)
					.mapToObj(index -> (Callable<Boolean>) () -> {
						ready.countDown();
						start.await();
						return adapter.tryAcquire(new Nickname("pikume"), "user-" + index, requestedAt);
					})
					.toList();
			var futures = requests.stream().map(executor::submit).toList();
			ready.await();
			start.countDown();

			long acquiredCount = 0;
			for (var future : futures) {
				if (future.get()) {
					acquiredCount++;
				}
			}

			assertThat(acquiredCount).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}
}
