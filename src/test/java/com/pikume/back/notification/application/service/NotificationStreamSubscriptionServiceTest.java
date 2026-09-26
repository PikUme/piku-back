package com.pikume.back.notification.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import com.pikume.back.notification.application.dto.NotificationStreamMessage;
import com.pikume.back.notification.application.exception.NotificationStreamSendException;
import com.pikume.back.notification.application.port.out.LoadNotificationSummaryPort;
import com.pikume.back.notification.application.port.out.RegisterNotificationStreamPort;
import com.pikume.back.notification.application.port.out.CloseNotificationStreamPort;
import com.pikume.back.notification.application.readmodel.NotificationSummaryView;
import com.pikume.back.notification.application.stream.NotificationStreamConnection;


import java.io.IOException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationStreamSubscriptionService - SSE 구독 관리")
class NotificationStreamSubscriptionServiceTest {

	@InjectMocks
	private NotificationStreamSubscriptionService notificationStreamSubscriptionService;

	@Mock
	private RegisterNotificationStreamPort registerNotificationStreamPort;
	@Mock
	private CloseNotificationStreamPort closeNotificationStreamPort;
	@Mock
	private LoadNotificationSummaryPort loadNotificationSummaryPort;
	@Mock
	private NotificationStreamConnection connection;

	@Nested
	@DisplayName("subscribe - SSE 구독")
	class Subscribe {

		@Test
		@DisplayName("SSE 구독 시 연결을 저장하고 미읽음 개수를 전송한다")
		void subscribeSendsUnreadCount() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(3L, false));

			notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);

			ArgumentCaptor<NotificationStreamMessage> messageCaptor = ArgumentCaptor.forClass(NotificationStreamMessage.class);
			then(registerNotificationStreamPort).should().registerNotificationStream(anyString(), eq("user-id"), eq(connection));
			then(connection).should().onCompletion(any());
			then(connection).should().onTimeout(any());
			then(connection).should().send(messageCaptor.capture());
			assertThat(messageCaptor.getValue().data()).isEqualTo(3L);
			then(loadNotificationSummaryPort).should().loadNotificationSummary("user-id");
		}

		@Test
		@DisplayName("구독 시 친구 요청이 있으면 FriendRequest 이벤트를 전송한다")
		void subscribeSendsFriendRequestEvent() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(1L, true));

			notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);

			ArgumentCaptor<NotificationStreamMessage> messageCaptor = ArgumentCaptor.forClass(NotificationStreamMessage.class);
			then(connection).should(times(2)).send(messageCaptor.capture());
			assertThat(messageCaptor.getAllValues().get(1).eventName()).isEqualTo("FriendRequest");
			assertThat(messageCaptor.getAllValues().get(1).data()).isEqualTo("on");
		}

		@Test
		@DisplayName("미읽음 알림이 없으면 0을 전송한다")
		void subscribeSendsZeroWhenNoUnread() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(0L, false));

			notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);

			ArgumentCaptor<NotificationStreamMessage> messageCaptor = ArgumentCaptor.forClass(NotificationStreamMessage.class);
			then(connection).should().send(messageCaptor.capture());
			assertThat(messageCaptor.getValue().data()).isEqualTo(0L);
		}

		@Test
		@DisplayName("초기 이벤트 전송 실패는 연결을 정리하고 예외로 올리지 않는다")
		void subscribeDeletesEmitterWithoutThrowingWhenInitialSendFails() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(3L, false));
			willThrow(new NotificationStreamSendException("SSE 전송 실패", new IOException("Broken pipe")))
					.given(connection).send(any(NotificationStreamMessage.class));

			assertThatCode(() -> notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection))
					.doesNotThrowAnyException();

			ArgumentCaptor<String> emitterIdCaptor = ArgumentCaptor.forClass(String.class);
			then(registerNotificationStreamPort).should().registerNotificationStream(emitterIdCaptor.capture(), eq("user-id"), eq(connection));
			then(closeNotificationStreamPort).should().closeNotificationStream("user-id", emitterIdCaptor.getValue());
		}

		@Test
		@DisplayName("예상하지 못한 초기 전송 예외는 숨기지 않는다")
		void subscribePropagatesUnexpectedInitialSendFailure() {
			RuntimeException unexpectedFailure = new IllegalStateException("serialization bug");
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(3L, false));
			willThrow(unexpectedFailure).given(connection).send(any(NotificationStreamMessage.class));

			assertThatThrownBy(() -> notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection))
					.isSameAs(unexpectedFailure);

			then(registerNotificationStreamPort).should().registerNotificationStream(anyString(), eq("user-id"), eq(connection));
			then(closeNotificationStreamPort).should().closeNotificationStream(eq("user-id"), anyString());
		}

		@Test
		@DisplayName("SSE 연결 완료 콜백이 발생하면 사용자의 emitter를 삭제한다")
		void subscribeDeletesEmitterWhenConnectionCompletionCallbackRuns() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(0L, false));

			notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);

			ArgumentCaptor<String> emitterIdCaptor = ArgumentCaptor.forClass(String.class);
			ArgumentCaptor<Runnable> completionHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
			then(registerNotificationStreamPort).should().registerNotificationStream(emitterIdCaptor.capture(), eq("user-id"), eq(connection));
			then(connection).should().onCompletion(completionHandlerCaptor.capture());

			completionHandlerCaptor.getValue().run();

			then(closeNotificationStreamPort).should().closeNotificationStream("user-id", emitterIdCaptor.getValue());
		}

		@Test
		@DisplayName("SSE 연결 오류 콜백이 발생하면 emitter를 삭제한다")
		@SuppressWarnings("unchecked")
		void subscribeDeletesEmitterWhenConnectionErrorCallbackRuns() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(0L, false));

			notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);

			ArgumentCaptor<String> emitterIdCaptor = ArgumentCaptor.forClass(String.class);
			ArgumentCaptor<Consumer<Throwable>> errorHandlerCaptor = ArgumentCaptor.forClass(Consumer.class);
			then(registerNotificationStreamPort).should().registerNotificationStream(emitterIdCaptor.capture(), eq("user-id"), eq(connection));
			then(connection).should().onError(errorHandlerCaptor.capture());

			errorHandlerCaptor.getValue().accept(
					new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어에 의해 중단되었습니다"));

			then(closeNotificationStreamPort).should().closeNotificationStream("user-id", emitterIdCaptor.getValue());
		}

		@Test
		@DisplayName("SSE 연결 타임아웃 콜백이 발생하면 사용자의 emitter를 삭제하고 연결을 완료한다")
		void subscribeDeletesEmitterAndCompletesConnectionWhenTimeoutCallbackRuns() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(0L, false));

			notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);

			ArgumentCaptor<String> emitterIdCaptor = ArgumentCaptor.forClass(String.class);
			ArgumentCaptor<Runnable> timeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
			then(registerNotificationStreamPort).should().registerNotificationStream(emitterIdCaptor.capture(), eq("user-id"), eq(connection));
			then(connection).should().onTimeout(timeoutHandlerCaptor.capture());

			timeoutHandlerCaptor.getValue().run();

			then(closeNotificationStreamPort).should().closeNotificationStream("user-id", emitterIdCaptor.getValue());
			then(connection).should().complete();
		}
	}

	@Nested
	@DisplayName("SSE 로그")
	class Logging {

		@Test
		@DisplayName("정상 구독 흐름을 구조화된 DEBUG 로그로 기록한다")
		void subscribeLogsStructuredNormalFlowAtDebug() {
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(1L, true));
			ArgumentCaptor<Runnable> completionHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
			Logger logger = (Logger) LoggerFactory.getLogger(NotificationStreamSubscriptionService.class);
			Level previousLevel = logger.getLevel();
			logger.setLevel(Level.DEBUG);
			ListAppender<ILoggingEvent> appender = attachLogAppender();

			try {
				notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);
				then(connection).should().onCompletion(completionHandlerCaptor.capture());
				completionHandlerCaptor.getValue().run();
			} finally {
				detachLogAppender(appender);
				logger.setLevel(previousLevel);
			}

			assertLogContains(appender, Level.DEBUG,
					"event=sse_subscription_requested",
					"outcome=accepted",
					"userId=user-id");
			assertLogContains(appender, Level.DEBUG,
					"event=sse_friend_request_notification_send_requested",
					"outcome=accepted",
					"userId=user-id",
					"resourceId=user-id_");
			assertLogContains(appender, Level.DEBUG,
					"event=sse_connection_completed",
					"outcome=success",
					"userId=user-id",
					"resourceId=user-id_");
		}

		@Test
		@DisplayName("비정상 구독 흐름을 예외 메시지 없이 구조화된 WARN 로그로 기록한다")
		@SuppressWarnings("unchecked")
		void subscribeLogsStructuredAbnormalFlowAtWarn() {
			String sensitiveSendMessage = "PRIVATE-SEND-FAILURE";
			String sensitiveConnectionMessage = "PRIVATE-CONNECTION-FAILURE";
			given(loadNotificationSummaryPort.loadNotificationSummary("user-id"))
					.willReturn(new NotificationSummaryView(1L, false));
			willThrow(new NotificationStreamSendException(sensitiveSendMessage, new IOException("Broken pipe")))
					.given(connection).send(any(NotificationStreamMessage.class));
			ArgumentCaptor<Consumer<Throwable>> errorHandlerCaptor = ArgumentCaptor.forClass(Consumer.class);
			ArgumentCaptor<Runnable> timeoutHandlerCaptor = ArgumentCaptor.forClass(Runnable.class);
			ListAppender<ILoggingEvent> appender = attachLogAppender();

			try {
				notificationStreamSubscriptionService.subscribeToNotifications("user-id", connection);
				then(connection).should().onError(errorHandlerCaptor.capture());
				then(connection).should().onTimeout(timeoutHandlerCaptor.capture());
				errorHandlerCaptor.getValue().accept(new IOException(sensitiveConnectionMessage));
				timeoutHandlerCaptor.getValue().run();
			} finally {
				detachLogAppender(appender);
			}

			assertLogContains(appender, Level.WARN,
					"event=sse_notification_delivery",
					"outcome=failed",
					"userId=user-id",
					"resourceId=user-id_",
					"reason=stream_send_failed",
					"exception=NotificationStreamSendException");
			assertLogContains(appender, Level.WARN,
					"event=sse_connection_closed",
					"outcome=failed",
					"userId=user-id",
					"resourceId=user-id_",
					"reason=connection_error",
					"exception=IOException");
			assertLogContains(appender, Level.WARN,
					"event=sse_connection_closed",
					"outcome=failed",
					"userId=user-id",
					"resourceId=user-id_",
					"reason=timeout");
			assertThat(appender.list)
					.extracting(ILoggingEvent::getFormattedMessage)
					.noneMatch(message -> message.contains(sensitiveSendMessage)
							|| message.contains(sensitiveConnectionMessage));
		}
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(NotificationStreamSubscriptionService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(NotificationStreamSubscriptionService.class);
		logger.detachAppender(appender);
	}

	private void assertLogContains(ListAppender<ILoggingEvent> appender, Level level, String... fragments) {
		assertThat(appender.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(level);
			assertThat(event.getFormattedMessage()).contains(fragments);
		});
	}
}
