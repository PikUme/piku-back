package com.pikume.back.notification.adapter.in.web;

import com.pikume.back.global.logging.RequestIdContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.pikume.back.notification.application.dto.NotificationStreamMessage;
import com.pikume.back.notification.application.exception.NotificationStreamSendException;
import com.pikume.back.notification.application.stream.NotificationStreamConnection;

import java.io.IOException;
import java.util.function.Consumer;

public class SseEmitterConnection implements NotificationStreamConnection {

	private static final String COMPLETED_EMITTER_MESSAGE = "ResponseBodyEmitter has already completed";

	private final SseEmitter emitter;
	private final RequestIdContext requestContext;

	public SseEmitterConnection(long timeoutMillis) {
		this.emitter = new SseEmitter(timeoutMillis);
		this.requestContext = RequestIdContext.capture();
	}

	public SseEmitter emitter() {
		return emitter;
	}

	@Override
	public void onCompletion(Runnable action) {
		emitter.onCompletion(requestContext.wrap(action));
	}

	@Override
	public void onTimeout(Runnable action) {
		emitter.onTimeout(requestContext.wrap(action));
	}

	@Override
	public void onError(Consumer<Throwable> action) {
		emitter.onError(requestContext.wrap(action));
	}

	@Override
	public void complete() {
		emitter.complete();
	}

	@Override
	public void send(NotificationStreamMessage message) {
		try {
			SseEmitter.SseEventBuilder builder = SseEmitter.event().id(message.eventId()).data(message.data());
			if (message.eventName() != null) {
				builder.name(message.eventName());
			}
			emitter.send(builder);
		} catch (IOException e) {
			throw new NotificationStreamSendException("SSE 전송 실패", e);
		} catch (IllegalStateException e) {
			if (isCompletedEmitterFailure(e)) {
				throw new NotificationStreamSendException("SSE 연결이 이미 종료되었습니다.", e);
			}
			throw e;
		}
	}

	private boolean isCompletedEmitterFailure(IllegalStateException exception) {
		String message = exception.getMessage();
		return message != null && message.contains(COMPLETED_EMITTER_MESSAGE);
	}
}
