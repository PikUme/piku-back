package com.pikume.back.notification.application.service;

import com.pikume.back.notification.application.dto.NotificationDeliveryRequest;
import com.pikume.back.notification.application.dto.NotificationSsePayload;
import com.pikume.back.notification.application.dto.NotificationStreamMessage;
import com.pikume.back.notification.application.dto.RecordNotificationCommand;
import com.pikume.back.notification.application.exception.NotificationSenderNotFoundException;
import com.pikume.back.notification.application.policy.NotificationPresentationPolicy;
import com.pikume.back.notification.application.port.in.RecordNotificationUseCase;
import com.pikume.back.notification.application.port.out.LoadNotificationDiaryContextsPort;
import com.pikume.back.notification.application.port.out.LoadNotificationSendersPort;
import com.pikume.back.notification.application.port.out.RecordNotificationPort;
import com.pikume.back.notification.application.port.out.ScheduleNotificationDeliveryPort;
import com.pikume.back.notification.application.readmodel.NotificationDiaryContextView;
import com.pikume.back.notification.application.readmodel.NotificationSenderView;
import com.pikume.back.notification.domain.Notification;
import com.pikume.back.notification.domain.vo.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationRecordingService implements RecordNotificationUseCase {

	private final RecordNotificationPort recordNotificationPort;
	private final LoadNotificationSendersPort loadNotificationSendersPort;
	private final LoadNotificationDiaryContextsPort loadNotificationDiaryContextsPort;
	private final ScheduleNotificationDeliveryPort scheduleNotificationDeliveryPort;
	private final NotificationPresentationPolicy presentationPolicy;

	@Override
	@Transactional
	public void recordNotification(RecordNotificationCommand command) {
		NotificationDiaryContextView diaryContext = loadDiaryContext(command.diaryId());
		if (command.diaryId() != null && diaryContext == null) {
			log.debug("event=notification_record_skipped outcome=skipped reason=diary_missing diaryId={}",
					command.diaryId());
			return;
		}

		Notification notification = new Notification(
				command.receiverId(),
				command.senderId(),
				NotificationType.valueOf(command.kind().name()),
				command.diaryId());
		Notification recorded = recordNotificationPort.recordNotification(notification);

		boolean anonymous = diaryContext != null && diaryContext.anonymous();
		NotificationSenderView sender = anonymous ? null : loadSender(command.senderId());
		String senderNickname = presentationPolicy.senderNickname(
				anonymous,
				sender != null ? sender.nickname() : null);
		String senderId = anonymous ? null : command.senderId();
		String senderAvatarUrl = sender != null ? sender.avatarUrl() : null;
		String message = presentationPolicy.messageFor(command.kind());
		NotificationSsePayload payload = new NotificationSsePayload(
				command.kind(),
				message,
				command.diaryId(),
				senderId,
				senderNickname,
				senderAvatarUrl,
				diaryContext != null ? diaryContext.thumbnailUrl() : null);
		String eventName = command.kind() == com.pikume.back.notification.application.dto.NotificationKind.FRIEND_REQUEST
				? "FriendRequest"
				: null;
		NotificationStreamMessage streamMessage = new NotificationStreamMessage(
				command.receiverId() + "_" + System.currentTimeMillis(),
				eventName,
				payload);
		NotificationDeliveryRequest deliveryRequest = new NotificationDeliveryRequest(
				recorded.getId(),
				command.receiverId(),
				streamMessage,
				senderNickname + message);
		scheduleNotificationDeliveryPort.scheduleNotificationDelivery(deliveryRequest);
	}

	private NotificationDiaryContextView loadDiaryContext(Long diaryId) {
		if (diaryId == null) {
			return null;
		}
		return loadNotificationDiaryContextsPort.loadNotificationDiaryContexts(Set.of(diaryId)).get(diaryId);
	}

	private NotificationSenderView loadSender(String senderId) {
		if (senderId == null || senderId.isBlank()) {
			throw new NotificationSenderNotFoundException(senderId);
		}
		Map<String, NotificationSenderView> senders =
				loadNotificationSendersPort.loadNotificationSenders(Set.of(senderId));
		NotificationSenderView sender = senders.get(senderId);
		if (sender == null) {
			throw new NotificationSenderNotFoundException(senderId);
		}
		return sender;
	}
}
