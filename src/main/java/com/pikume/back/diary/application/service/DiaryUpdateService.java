package com.pikume.back.diary.application.service;

import com.pikume.back.diary.application.dto.DiaryUpdatedResult;
import com.pikume.back.diary.application.dto.UpdateDiaryCommand;
import com.pikume.back.diary.application.exception.DiaryAccessDeniedException;
import com.pikume.back.diary.application.exception.DiaryInvalidRequestException;
import com.pikume.back.diary.application.exception.DiaryNotFoundException;
import com.pikume.back.diary.application.port.in.UpdateDiaryUseCase;
import com.pikume.back.diary.application.port.out.AnalyzeDiaryContentPort;
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
public class DiaryUpdateService implements UpdateDiaryUseCase {

	private final LoadDiaryForCommandPort loadDiaryPort;
	private final RecordDiaryPort recordDiaryPort;
	private final DiaryPhotoRelocationService relocationService;
	private final AnalyzeDiaryContentPort analysisPort;
	private final TransactionCompletionPort transactionCompletionPort;

	@Override
	@Transactional
	public DiaryUpdatedResult updateDiary(Long diaryId, UpdateDiaryCommand command, String userId) {
		if (command == null) {
			throw new DiaryInvalidRequestException("일기 수정 요청은 필수입니다.");
		}
		Diary diary = loadOwnedDiary(diaryId, userId);
		DiaryPhotoRelocationService.Relocation relocation = relocationService.relocate(diary, command.status());
		try {
			diary.updateContentAndStatus(command.content(), command.status());
			Diary recorded = recordDiaryPort.record(diary);
			relocationService.completeAfterTransaction(relocation);
			transactionCompletionPort.runAfterCommit(
					() -> analysisPort.analyze(recorded.getId(), recorded.getContent()),
					exception -> log.warn(
							"event=diary_content_analysis_failed outcome=failed resourceId={} exception={}",
							recorded.getId(),
							exception.getClass().getSimpleName()));
			return new DiaryUpdatedResult(recorded.getId(), recorded.getStatus(), recorded.getContent());
		} catch (RuntimeException exception) {
			relocationService.rollback(relocation);
			throw exception;
		}
	}

	private Diary loadOwnedDiary(Long diaryId, String userId) {
		Diary diary = loadDiaryPort.findActiveById(diaryId).orElseThrow(DiaryNotFoundException::new);
		if (!diary.isOwner(userId)) {
			throw new DiaryAccessDeniedException();
		}
		return diary;
	}

}
