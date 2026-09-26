package com.pikume.back.diary.application.service;

import com.pikume.back.diary.application.exception.DiaryImageRelocationException;
import com.pikume.back.diary.application.port.out.LoadDiaryForCommandPort;
import com.pikume.back.diary.application.port.out.RecordDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.RelocateDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.TransactionCompletionPort;
import com.pikume.back.diary.domain.Diary;
import com.pikume.back.diary.domain.Photo;
import com.pikume.back.diary.domain.vo.DiaryVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiaryPhotoRelocationService {

	private final LoadDiaryForCommandPort loadDiaryPort;
	private final RecordDiaryPhotoPort recordDiaryPhotoPort;
	private final RelocateDiaryPhotoPort photoRelocationPort;
	private final TransactionCompletionPort transactionCompletionPort;

	public Relocation relocate(Diary diary, DiaryVisibility targetVisibility) {
		if (!diary.getStatus().requiresPhotoScopeTransitionTo(targetVisibility)) {
			return Relocation.empty();
		}

		List<String> copiedKeys = new ArrayList<>();
		List<String> oldKeys = new ArrayList<>();
		List<PhotoTransition> transitions = new ArrayList<>();
		try {
			for (Photo photo : loadDiaryPort.findPhotosByDiaryId(diary.getId())) {
				PhotoTransition transition = planPhotoRelocation(photo, targetVisibility, copiedKeys, oldKeys);
				if (transition != null) {
					transitions.add(transition);
				}
			}
			for (PhotoTransition transition : transitions) {
				transition.apply();
				recordDiaryPhotoPort.record(transition.photo());
			}
			return new Relocation(distinct(copiedKeys), distinct(oldKeys));
		} catch (RuntimeException exception) {
			transitions.forEach(PhotoTransition::restore);
			deleteBestEffort(copiedKeys, "diary_photo_relocation_rollback_failed");
			if (exception instanceof DiaryImageRelocationException relocationException) {
				throw relocationException;
			}
			throw new DiaryImageRelocationException("일기 이미지 공개범위 변경 중 오류가 발생했습니다.", exception);
		}
	}

	public void completeAfterTransaction(Relocation relocation) {
		if (relocation == null || relocation.isEmpty()) {
			return;
		}
		transactionCompletionPort.runAfterCompletion(
				() -> deleteBestEffort(relocation.oldObjectKeys(), "diary_photo_old_object_cleanup_failed"),
				() -> deleteBestEffort(relocation.copiedObjectKeys(), "diary_photo_relocation_rollback_failed"));
	}

	public void rollback(Relocation relocation) {
		if (relocation != null) {
			deleteBestEffort(relocation.copiedObjectKeys(), "diary_photo_relocation_rollback_failed");
		}
	}

	private PhotoTransition planPhotoRelocation(
			Photo photo,
			DiaryVisibility targetVisibility,
			List<String> copiedKeys,
			List<String> oldKeys) {
		String oldOriginal = photo.getUrl();
		String newOriginal = copy(oldOriginal, targetVisibility, photo, copiedKeys);
		String oldOptimized = photo.getOptimizedUrl();
		String newOptimized = oldOptimized;
		if (hasText(oldOptimized)) {
			newOptimized = Objects.equals(oldOriginal, oldOptimized)
					? newOriginal
					: copy(oldOptimized, targetVisibility, photo, copiedKeys);
		}
		if (Objects.equals(oldOriginal, newOriginal) && Objects.equals(oldOptimized, newOptimized)) {
			return null;
		}
		addIfReplaced(oldKeys, oldOriginal, newOriginal);
		addIfReplaced(oldKeys, oldOptimized, newOptimized);
		return new PhotoTransition(photo, oldOriginal, oldOptimized, newOriginal, newOptimized);
	}

	private String copy(String sourceKey, DiaryVisibility targetVisibility, Photo photo, List<String> copiedKeys) {
		if (!hasText(sourceKey)) {
			return sourceKey;
		}
		String copiedKey = photoRelocationPort.copyToVisibilityScope(sourceKey, targetVisibility, photo.getSourceType());
		if (!Objects.equals(sourceKey, copiedKey)) {
			copiedKeys.add(copiedKey);
		}
		return copiedKey;
	}

	private void addIfReplaced(List<String> oldKeys, String oldKey, String newKey) {
		if (hasText(oldKey) && !Objects.equals(oldKey, newKey)) {
			oldKeys.add(oldKey);
		}
	}

	private void deleteBestEffort(List<String> objectKeys, String event) {
		for (String objectKey : distinct(objectKeys)) {
			try {
				photoRelocationPort.delete(objectKey);
			} catch (RuntimeException exception) {
				log.error("event={} outcome=failed exception={}", event, exception.getClass().getSimpleName());
			}
		}
	}

	private List<String> distinct(List<String> objectKeys) {
		return objectKeys == null ? List.of() : List.copyOf(new LinkedHashSet<>(objectKeys));
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public record Relocation(List<String> copiedObjectKeys, List<String> oldObjectKeys) {

		public Relocation {
			copiedObjectKeys = copiedObjectKeys == null ? List.of() : List.copyOf(copiedObjectKeys);
			oldObjectKeys = oldObjectKeys == null ? List.of() : List.copyOf(oldObjectKeys);
		}

		public static Relocation empty() {
			return new Relocation(List.of(), List.of());
		}

		public boolean isEmpty() {
			return copiedObjectKeys.isEmpty() && oldObjectKeys.isEmpty();
		}
	}

	private record PhotoTransition(
			Photo photo,
			String oldOriginal,
			String oldOptimized,
			String newOriginal,
			String newOptimized) {

		private void apply() {
			photo.updateObjectKeys(newOriginal, newOptimized);
		}

		private void restore() {
			photo.updateObjectKeys(oldOriginal, oldOptimized);
		}
	}
}
