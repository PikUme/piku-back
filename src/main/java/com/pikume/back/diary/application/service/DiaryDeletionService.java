package com.pikume.back.diary.application.service;

import com.pikume.back.diary.application.exception.DiaryAccessDeniedException;
import com.pikume.back.diary.application.exception.DiaryNotFoundException;
import com.pikume.back.diary.application.port.in.DeleteDiaryUseCase;
import com.pikume.back.diary.application.port.out.DeleteDiaryNotificationPort;
import com.pikume.back.diary.application.port.out.LoadDiaryForCommandPort;
import com.pikume.back.diary.application.port.out.RecordDiaryPort;
import com.pikume.back.diary.application.port.out.TransactionCompletionPort;
import com.pikume.back.diary.domain.Diary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiaryDeletionService implements DeleteDiaryUseCase {

	private final LoadDiaryForCommandPort loadDiaryPort;
	private final RecordDiaryPort recordDiaryPort;
	private final DeleteDiaryNotificationPort notificationPort;
	private final TransactionCompletionPort transactionCompletionPort;

	@Override
	@Transactional
	public void deleteDiary(Long diaryId, String userId) {
		Diary diary = loadOwnedDiary(diaryId, userId);
		diary.delete();
		recordDiaryPort.record(diary);
		transactionCompletionPort.runAfterCommit(
				() -> notificationPort.deleteNotificationsByDiaryId(diaryId),
				exception -> log.warn(
						"event=diary_notification_cleanup_failed outcome=failed resourceId={} exception={}",
						diaryId,
						exception.getClass().getSimpleName()));
	}

	private Diary loadOwnedDiary(Long diaryId, String userId) {
		Diary diary = loadDiaryPort.findActiveById(diaryId).orElseThrow(DiaryNotFoundException::new);
		if (!diary.isOwner(userId)) {
			throw new DiaryAccessDeniedException();
		}
		return diary;
	}
}
