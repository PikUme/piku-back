package com.pikume.back.notification.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.pikume.back.notification.application.dto.NotificationStreamMessage;
import com.pikume.back.notification.application.exception.NotificationStreamSendException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SseEmitterConnection")
class SseEmitterConnectionTest {

	@Test
	@DisplayName("이미 완료된 emitter 전송 실패는 스트림 전송 실패로 변환한다")
	void sendConvertsCompletedEmitterFailure() {
		SseEmitterConnection connection = new SseEmitterConnection(1000L);
		connection.complete();

		assertThatThrownBy(() -> connection.send(new NotificationStreamMessage("event-id", null, "data")))
				.isInstanceOf(NotificationStreamSendException.class)
				.hasCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("예상하지 못한 IllegalStateException은 숨기지 않는다")
	void sendPropagatesUnexpectedIllegalStateException() {
		SseEmitterConnection connection = new SseEmitterConnection(1000L);
		IllegalStateException unexpectedFailure = new IllegalStateException("serialization bug");
		ReflectionTestUtils.setField(connection, "emitter", new ThrowingSseEmitter(unexpectedFailure));

		assertThatThrownBy(() -> connection.send(new NotificationStreamMessage("event-id", null, "data")))
				.isSameAs(unexpectedFailure);
	}

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(strings = {"completionCallback", "timeoutCallback", "errorCallback"})
    void lifecycleCallbacksUseConnectionContextAndRestoreWorker(String callbackName) {
        MDC.put("requestId", "11111111111111111111111111111111");
        SseEmitterConnection connection = new SseEmitterConnection(1000L);
        Runnable action = () -> {
            assertThat(MDC.get("requestId")).isEqualTo("11111111111111111111111111111111");
            throw new IllegalStateException("callback failure");
        };
        connection.onCompletion(action);
        connection.onTimeout(action);
        connection.onError(error -> action.run());
        MDC.put("requestId", "22222222222222222222222222222222");
        Object callback = ReflectionTestUtils.getField(connection.emitter(), callbackName);

        assertThatThrownBy(() -> {
            if (callback instanceof Runnable runnable) {
                runnable.run();
            } else {
                ((Consumer<Throwable>) callback).accept(new IllegalArgumentException("transport"));
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("callback failure");
        assertThat(MDC.get("requestId")).isEqualTo("22222222222222222222222222222222");
    }

	private static class ThrowingSseEmitter extends SseEmitter {

		private final RuntimeException failure;

		private ThrowingSseEmitter(RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			throw failure;
		}
	}
}
