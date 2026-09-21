package com.pikume.back.notification.adapter.in.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.pikume.back.notification.application.dto.NotificationKind;
import com.pikume.back.notification.application.dto.RecordNotificationCommand;
import com.pikume.back.notification.application.port.in.RecordNotificationUseCase;
import com.pikume.back.social.application.event.SocialNotificationEvent;

/**
 * Social Application의 공개 알림 사건을 Notification 기록 명령으로 번역합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SocialEventListener {

	private final RecordNotificationUseCase recordNotificationUseCase;

	@EventListener
	public void handleCommentCreated(SocialNotificationEvent.CommentCreated event) {
		log.debug("event=comment_created_received receiverUserId={} senderUserId={} resourceId={}",
				event.receiverId(), event.senderId(), event.diaryId());

		NotificationKind kind = event.isReply() ? NotificationKind.REPLY : NotificationKind.COMMENT;
		recordNotificationUseCase.recordNotification(new RecordNotificationCommand(
				event.receiverId(), kind, event.senderId(), event.diaryId()));
	}

	@EventListener
	public void handleLikeCreated(SocialNotificationEvent.LikeCreated event) {
		log.debug("event=like_created_received receiverUserId={} senderUserId={} resourceId={}",
				event.receiverId(), event.senderId(), event.diaryId());

		recordNotificationUseCase.recordNotification(new RecordNotificationCommand(
				event.receiverId(), NotificationKind.LIKE,
				event.senderId(), event.diaryId()));
	}

	@EventListener
	public void handleFriendRequest(SocialNotificationEvent.FriendRequest event) {
		log.debug("event=friend_request_received receiverUserId={} senderUserId={}",
				event.receiverId(), event.senderId());

		recordNotificationUseCase.recordNotification(new RecordNotificationCommand(
				event.receiverId(), NotificationKind.FRIEND_REQUEST,
				event.senderId(), null));
	}

	@EventListener
	public void handleFriendAccepted(SocialNotificationEvent.FriendAccepted event) {
		log.debug("event=friend_accepted_received receiverUserId={} senderUserId={}",
				event.receiverId(), event.senderId());

		recordNotificationUseCase.recordNotification(new RecordNotificationCommand(
				event.receiverId(), NotificationKind.FRIEND_ACCEPT,
				event.senderId(), null));
	}
}
