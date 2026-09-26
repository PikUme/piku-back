package com.pikume.back.notification.adapter.out.stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import com.pikume.back.notification.application.dto.NotificationStreamMessage;
import com.pikume.back.notification.application.exception.NotificationStreamSendException;
import com.pikume.back.notification.application.stream.NotificationStreamConnection;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

@DisplayName("InMemoryNotificationStreamAdapter")
class InMemoryNotificationStreamAdapterTest {

	@Test
	@DisplayName("동일 사용자의 모든 연결에 알림을 전송한다")
	void sendToUserSendsMessageToAllUserConnections() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection firstConnection = mock(NotificationStreamConnection.class);
		NotificationStreamConnection secondConnection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		adapter.registerNotificationStream("first-emitter-id", "user-id", firstConnection);
		adapter.registerNotificationStream("second-emitter-id", "user-id", secondConnection);

		adapter.deliverNotificationStream("user-id", message);

		then(firstConnection).should().send(message);
		then(secondConnection).should().send(message);
	}

	@Test
	@DisplayName("알림 전송 요청을 구조화된 DEBUG 로그로 기록한다")
	void sendToUserLogsStructuredSendRequestAtDebug() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection connection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		adapter.registerNotificationStream("emitter-id", "user-id", connection);
		Logger logger = (Logger) LoggerFactory.getLogger(InMemoryNotificationStreamAdapter.class);
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			adapter.deliverNotificationStream("user-id", message);
		} finally {
			detachLogAppender(appender);
			logger.setLevel(previousLevel);
		}

		assertThat(appender.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
			assertThat(event.getFormattedMessage()).contains(
					"event=sse_notification_send_requested",
					"outcome=accepted",
					"userId=user-id",
					"resourceId=emitter-id");
		});
	}

	@Test
	@DisplayName("다른 사용자의 연결에는 알림을 전송하지 않는다")
	void sendToUserDoesNotSendMessageToOtherUserConnection() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection targetConnection = mock(NotificationStreamConnection.class);
		NotificationStreamConnection otherUserConnection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		adapter.registerNotificationStream("target-emitter-id", "target-user-id", targetConnection);
		adapter.registerNotificationStream("other-emitter-id", "other-user-id", otherUserConnection);

		adapter.deliverNotificationStream("target-user-id", message);

		then(targetConnection).should().send(message);
		then(otherUserConnection).should(times(0)).send(message);
	}

	@Test
	@DisplayName("사용자의 특정 연결만 삭제한다")
	void deleteRemovesOnlySelectedUserConnection() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection deletedConnection = mock(NotificationStreamConnection.class);
		NotificationStreamConnection remainingConnection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		adapter.registerNotificationStream("deleted-emitter-id", "user-id", deletedConnection);
		adapter.registerNotificationStream("remaining-emitter-id", "user-id", remainingConnection);

		adapter.closeNotificationStream("user-id", "deleted-emitter-id");
		adapter.deliverNotificationStream("user-id", message);

		then(deletedConnection).should(times(0)).send(message);
		then(remainingConnection).should().send(message);
	}

	@Test
	@DisplayName("마지막 연결 삭제 후 같은 사용자의 새 연결을 저장할 수 있다")
	void saveRegistersNewConnectionAfterDeletingLastUserConnection() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection deletedConnection = mock(NotificationStreamConnection.class);
		NotificationStreamConnection newConnection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		adapter.registerNotificationStream("deleted-emitter-id", "user-id", deletedConnection);
		adapter.closeNotificationStream("user-id", "deleted-emitter-id");

		adapter.registerNotificationStream("new-emitter-id", "user-id", newConnection);
		adapter.deliverNotificationStream("user-id", message);

		then(deletedConnection).should(times(0)).send(message);
		then(newConnection).should().send(message);
	}

	@Test
	@DisplayName("예상하지 못한 전송 예외는 숨기지 않는다")
	void sendToUserPropagatesUnexpectedSendFailure() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection connection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		RuntimeException unexpectedFailure = new IllegalStateException("serialization bug");
		adapter.registerNotificationStream("emitter-id", "user-id", connection);
		willThrow(unexpectedFailure).given(connection).send(message);

		assertThatThrownBy(() -> adapter.deliverNotificationStream("user-id", message))
				.isSameAs(unexpectedFailure);
	}

	@Test
	@DisplayName("스트림 전송 실패는 실패한 연결만 삭제하고 정상 연결을 유지한다")
	void sendToUserDeletesOnlyFailedConnectionWhenStreamSendFails() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection failedConnection = mock(NotificationStreamConnection.class);
		NotificationStreamConnection healthyConnection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		adapter.registerNotificationStream("failed-emitter-id", "user-id", failedConnection);
		adapter.registerNotificationStream("healthy-emitter-id", "user-id", healthyConnection);
		willThrow(new NotificationStreamSendException("SSE 전송 실패", new IOException("Broken pipe")))
				.given(failedConnection).send(message);

		assertThatCode(() -> adapter.deliverNotificationStream("user-id", message))
				.doesNotThrowAnyException();
		then(healthyConnection).should().send(message);
		clearInvocations(failedConnection, healthyConnection);

		adapter.deliverNotificationStream("user-id", message);

		then(failedConnection).should(times(0)).send(message);
		then(healthyConnection).should().send(message);
	}

	@Test
	@DisplayName("스트림 전송 실패를 예외 메시지 없이 구조화된 WARN 로그로 기록한다")
	void sendToUserLogsStructuredStreamFailureAtWarn() {
		InMemoryNotificationStreamAdapter adapter = new InMemoryNotificationStreamAdapter();
		NotificationStreamConnection connection = mock(NotificationStreamConnection.class);
		NotificationStreamMessage message = new NotificationStreamMessage("event-id", null, "data");
		String sensitiveMessage = "PRIVATE-STREAM-FAILURE";
		adapter.registerNotificationStream("emitter-id", "user-id", connection);
		willThrow(new NotificationStreamSendException(sensitiveMessage, new IOException("Broken pipe")))
				.given(connection).send(message);
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			adapter.deliverNotificationStream("user-id", message);
		} finally {
			detachLogAppender(appender);
		}

		assertThat(appender.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains(
					"event=sse_notification_delivery",
					"outcome=failed",
					"userId=user-id",
					"resourceId=emitter-id",
					"reason=stream_send_failed",
					"exception=NotificationStreamSendException");
		});
		assertThat(appender.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.noneMatch(logMessage -> logMessage.contains(sensitiveMessage));
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(InMemoryNotificationStreamAdapter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(InMemoryNotificationStreamAdapter.class);
		logger.detachAppender(appender);
	}
}
