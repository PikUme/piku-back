package com.pikume.back.diary.application.service;

import com.pikume.back.diary.application.dto.CreateDiaryCommand;
import com.pikume.back.diary.application.dto.DiaryCreatedResult;
import com.pikume.back.diary.application.dto.DiaryImageCommand;
import com.pikume.back.diary.application.dto.DiaryPhotoUpload;
import com.pikume.back.diary.application.exception.DiaryInvalidRequestException;
import com.pikume.back.diary.application.exception.DuplicateDiaryException;
import com.pikume.back.diary.application.policy.DiaryImageFilePolicy;
import com.pikume.back.diary.application.port.in.CreateDiaryUseCase;
import com.pikume.back.diary.application.port.out.AnalyzeDiaryContentPort;
import com.pikume.back.diary.application.port.out.LoadDiaryForCommandPort;
import com.pikume.back.diary.application.port.out.LoadFriendshipForDiaryPort;
import com.pikume.back.diary.application.port.out.ManageGeneratedImageForDiaryPort;
import com.pikume.back.diary.application.port.out.RecordDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.RecordDiaryPort;
import com.pikume.back.diary.application.port.out.RelocateDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.SendDiaryNotificationPort;
import com.pikume.back.diary.application.port.out.StoreDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.TransactionCompletionPort;
import com.pikume.back.diary.domain.Diary;
import com.pikume.back.diary.domain.Photo;
import com.pikume.back.diary.domain.vo.DiaryPhotoType;
import com.pikume.back.diary.domain.vo.DiaryVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiaryCreationService implements CreateDiaryUseCase {

	private final LoadDiaryForCommandPort loadDiaryPort;
	private final RecordDiaryPort recordDiaryPort;
	private final RecordDiaryPhotoPort recordDiaryPhotoPort;
	private final StoreDiaryPhotoPort storeDiaryPhotoPort;
	private final RelocateDiaryPhotoPort relocateDiaryPhotoPort;
	private final ManageGeneratedImageForDiaryPort generatedImagePort;
	private final LoadFriendshipForDiaryPort friendshipPort;
	private final SendDiaryNotificationPort notificationPort;
	private final AnalyzeDiaryContentPort analysisPort;
	private final TransactionCompletionPort transactionCompletionPort;
	private final DiaryImageFilePolicy imageFilePolicy;

	@Override
	@Transactional
	public DiaryCreatedResult createDiary(CreateDiaryCommand command, List<DiaryPhotoUpload> photos, String userId) {
		validate(command, photos, userId);

		Diary diary = recordDiaryPort.record(Diary.create(command.content(), command.status(), command.date(), userId));
		attachPhotos(diary, command.imageInfos(), photos);
		schedulePostCommitTasks(diary);
		return new DiaryCreatedResult(diary.getId(), diary.getContent());
	}

	private void validate(CreateDiaryCommand command, List<DiaryPhotoUpload> photos, String userId) {
		if (command == null) {
			throw new DiaryInvalidRequestException("일기 생성 요청은 필수입니다.");
		}
		if (userId == null || userId.isBlank()) {
			throw new DiaryInvalidRequestException("작성자 식별자는 필수입니다.");
		}
		if (command.status() == null) {
			throw new DiaryInvalidRequestException("공개범위는 필수입니다.");
		}
		if (command.content() == null || command.content().isBlank()) {
			throw new DiaryInvalidRequestException("일기 내용은 비어 있을 수 없습니다.");
		}
		if (command.content().length() > 500) {
			throw new DiaryInvalidRequestException("일기 내용은 최대 500자까지 입력할 수 있습니다.");
		}
		if (command.date() == null) {
			throw new DiaryInvalidRequestException("일기 날짜는 필수입니다.");
		}
		List<DiaryPhotoUpload> uploads = photos == null ? List.of() : photos;
		uploads.forEach(imageFilePolicy::validate);
		if (loadDiaryPort.findActiveByUserIdAndDate(userId, command.date()).isPresent()) {
			throw new DuplicateDiaryException(command.date());
		}
		if (command.date().isAfter(LocalDate.now())) {
			throw new DiaryInvalidRequestException("미래 날짜에 일기를 작성할 수 없습니다: " + command.date());
		}
		validateImageCommands(command.imageInfos(), uploads, userId);
	}

	private void validateImageCommands(List<DiaryImageCommand> imageCommands, List<DiaryPhotoUpload> photos, String userId) {
		List<DiaryImageCommand> commands = imageCommands == null ? List.of() : imageCommands;
		Set<Integer> orders = new HashSet<>();
		Set<Integer> userPhotoIndexes = new HashSet<>();
		Set<Long> aiPhotoIds = new HashSet<>();
		for (DiaryImageCommand image : commands) {
			if (image == null || image.type() == null || image.order() == null
					|| image.order() < 0 || !orders.add(image.order())) {
				throw new DiaryInvalidRequestException("이미지 정보 또는 순서가 올바르지 않습니다.");
			}
			if (image.type() == DiaryPhotoType.AI_IMAGE) {
				if (image.aiPhotoId() == null || image.aiPhotoId() <= 0) {
					throw new DiaryInvalidRequestException("유효하지 않은 AI 사진 ID: " + image.aiPhotoId());
				}
				if (!aiPhotoIds.add(image.aiPhotoId())) {
					throw new DiaryInvalidRequestException("중복된 AI 사진 ID: " + image.aiPhotoId());
				}
				if (!generatedImagePort.isGeneratedImageAvailableForDiary(image.aiPhotoId(), userId)) {
					throw new DiaryInvalidRequestException("유효하지 않은 AI 사진 ID: " + image.aiPhotoId());
				}
			} else {
				if (image.photoIndex() == null || image.photoIndex() < 0 || image.photoIndex() >= photos.size()) {
					throw new DiaryInvalidRequestException("유효하지 않은 사용자 사진 인덱스: " + image.photoIndex());
				}
				if (!userPhotoIndexes.add(image.photoIndex())) {
					throw new DiaryInvalidRequestException("중복된 사용자 사진 인덱스: " + image.photoIndex());
				}
			}
		}
		for (int expectedOrder = 0; expectedOrder < commands.size(); expectedOrder++) {
			if (!orders.contains(expectedOrder)) {
				throw new DiaryInvalidRequestException("이미지 순서는 0부터 빠짐없이 이어져야 합니다.");
			}
		}
		if (userPhotoIndexes.size() != photos.size()) {
			throw new DiaryInvalidRequestException("사용자 사진 개수와 이미지 정보 개수가 일치하지 않습니다.");
		}
	}

	private void attachPhotos(Diary diary, List<DiaryImageCommand> imageCommands, List<DiaryPhotoUpload> uploads) {
		List<DiaryImageCommand> commands = new ArrayList<>(imageCommands == null ? List.of() : imageCommands);
		commands.sort(Comparator.comparing(DiaryImageCommand::order));
		List<DiaryPhotoUpload> photos = uploads == null ? List.of() : uploads;
		List<String> newObjectKeys = new ArrayList<>();
		List<String> oldObjectKeys = new ArrayList<>();
		try {
			for (DiaryImageCommand image : commands) {
				if (image.type() == DiaryPhotoType.AI_IMAGE) {
					attachGeneratedPhoto(diary, image, newObjectKeys, oldObjectKeys);
				} else {
					attachUploadedPhoto(diary, photos.get(image.photoIndex()), image.order(), newObjectKeys);
				}
			}
		} catch (RuntimeException exception) {
			deleteBestEffort(newObjectKeys, "diary_photo_creation_cleanup_failed");
			throw exception;
		}
		if (!newObjectKeys.isEmpty() || !oldObjectKeys.isEmpty()) {
			transactionCompletionPort.runAfterCompletion(
					() -> deleteBestEffort(oldObjectKeys, "diary_generated_photo_source_cleanup_failed"),
					() -> deleteBestEffort(newObjectKeys, "diary_photo_creation_rollback_failed"));
		}
	}

	private void attachUploadedPhoto(
			Diary diary,
			DiaryPhotoUpload upload,
			Integer order,
			List<String> newObjectKeys) {
		String objectKey = storeDiaryPhotoPort.store(upload, diary.getStatus());
		newObjectKeys.add(objectKey);
		recordPhoto(diary, objectKey, order, DiaryPhotoType.USER_IMAGE);
	}

	private void attachGeneratedPhoto(
			Diary diary,
			DiaryImageCommand image,
			List<String> newObjectKeys,
			List<String> oldObjectKeys) {
		String sourceObjectKey = generatedImagePort.loadGeneratedImagePath(image.aiPhotoId());
		String objectKey = relocateDiaryPhotoPort.copyToVisibilityScope(
				sourceObjectKey,
				diary.getStatus(),
				DiaryPhotoType.AI_IMAGE);
		if (!Objects.equals(sourceObjectKey, objectKey)) {
			newObjectKeys.add(objectKey);
			oldObjectKeys.add(sourceObjectKey);
			generatedImagePort.updateGeneratedImagePath(image.aiPhotoId(), objectKey);
		}
		recordPhoto(diary, objectKey, image.order(), DiaryPhotoType.AI_IMAGE);
		generatedImagePort.attachGeneratedImageToDiary(image.aiPhotoId(), diary.getId());
	}

	private void deleteBestEffort(List<String> objectKeys, String event) {
		objectKeys.stream()
				.filter(key -> key != null && !key.isBlank())
				.distinct()
				.forEach(key -> {
					try {
						relocateDiaryPhotoPort.delete(key);
					} catch (RuntimeException exception) {
						log.error("event={} outcome=failed exception={}", event, exception.getClass().getSimpleName());
					}
				});
	}

	private void recordPhoto(Diary diary, String objectKey, Integer order, DiaryPhotoType sourceType) {
		Photo photo = new Photo(diary, objectKey, order, sourceType);
		photo.updateRepresent(order != null && order == 0);
		recordDiaryPhotoPort.record(photo);
	}

	private void schedulePostCommitTasks(Diary diary) {
		if (diary.getStatus() == DiaryVisibility.FRIENDS) {
			transactionCompletionPort.runAfterCommit(
					() -> notifyFriends(diary),
					exception -> log.warn(
							"event=diary_friend_notification_failed outcome=failed resourceId={} exception={}",
							diary.getId(),
							exception.getClass().getSimpleName()));
		}
		transactionCompletionPort.runAfterCommit(
				() -> analysisPort.analyze(diary.getId(), diary.getContent()),
				exception -> log.warn(
						"event=diary_content_analysis_failed outcome=failed resourceId={} exception={}",
						diary.getId(),
						exception.getClass().getSimpleName()));
	}

	private void notifyFriends(Diary diary) {
		List<String> friendIds = friendshipPort.findFriendIds(diary.getUserId());
		notificationPort.notifyFriendsOfNewDiary(friendIds, diary.getUserId(), diary.getId());
	}
}
