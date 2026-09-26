package com.pikume.back.notification.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.pikume.back.notification.application.dto.NotificationStreamMessage;
import com.pikume.back.notification.application.exception.NotificationStreamSendException;
import com.pikume.back.notification.application.port.in.SubscribeNotificationStreamUseCase;
import com.pikume.back.notification.application.port.out.CloseNotificationStreamPort;
import com.pikume.back.notification.application.port.out.LoadNotificationSummaryPort;
import com.pikume.back.notification.application.port.out.RegisterNotificationStreamPort;
import com.pikume.back.notification.application.readmodel.NotificationSummaryView;
import com.pikume.back.notification.application.stream.NotificationStreamConnection;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationStreamSubscriptionService implements SubscribeNotificationStreamUseCase {

	private final RegisterNotificationStreamPort registerNotificationStreamPort;
	private final CloseNotificationStreamPort closeNotificationStreamPort;
	private final LoadNotificationSummaryPort loadNotificationSummaryPort;

	@Override
	public void subscribeToNotifications(String userId, NotificationStreamConnection connection) {
		log.debug("event=sse_subscription_requested outcome=accepted userId={}", userId);
		String emitterId = userId + "_" + System.currentTimeMillis();
		registerNotificationStreamPort.registerNotificationStream(emitterId, userId, connection);

		connection.onCompletion(() -> {
			log.debug("event=sse_connection_completed outcome=success userId={} resourceId={}",
					userId, emitterId);
			closeNotificationStreamPort.closeNotificationStream(userId, emitterId);
		});

		connection.onError(error -> {
			log.warn("event=sse_connection_closed outcome=failed userId={} resourceId={} "
					+ "reason=connection_error exception={}",
					userId, emitterId, error.getClass().getSimpleName());
			closeNotificationStreamPort.closeNotificationStream(userId, emitterId);
		});

		connection.onTimeout(() -> {
			log.warn("event=sse_connection_closed outcome=failed userId={} resourceId={} reason=timeout",
					userId, emitterId);
			closeNotificationStreamPort.closeNotificationStream(userId, emitterId);
			connection.complete();
		});

		NotificationSummaryView summary = loadNotificationSummaryPort.loadNotificationSummary(userId);
		String eventId = userId + "_" + System.currentTimeMillis();
		if (!send(userId, emitterId, connection, new NotificationStreamMessage(eventId, null, summary.unreadCount()))) {
			return;
		}

		if (summary.hasFriendRequest()) {
			String friendEventId = userId + "_" + System.currentTimeMillis();
			log.debug("event=sse_friend_request_notification_send_requested outcome=accepted "
					+ "userId={} resourceId={}", userId, friendEventId);
			send(userId, emitterId, connection,
					new NotificationStreamMessage(friendEventId, "FriendRequest", "on"));
		}
	}

	private boolean send(String userId, String emitterId, NotificationStreamConnection connection,
			NotificationStreamMessage message) {
		try {
			connection.send(message);
			return true;
		} catch (NotificationStreamSendException e) {
			log.warn("event=sse_notification_delivery outcome=failed userId={} resourceId={} "
					+ "reason=stream_send_failed exception={}",
					userId, emitterId, e.getClass().getSimpleName());
			closeNotificationStreamPort.closeNotificationStream(userId, emitterId);
			return false;
		} catch (RuntimeException e) {
			closeNotificationStreamPort.closeNotificationStream(userId, emitterId);
			throw e;
		}
	}
}
