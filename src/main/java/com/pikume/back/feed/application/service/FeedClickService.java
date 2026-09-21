package com.pikume.back.feed.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pikume.back.feed.application.port.in.RecordFeedClickUseCase;
import com.pikume.back.feed.application.port.out.LoadFeedClickHistoryPort;
import com.pikume.back.feed.application.port.out.LoadFeedDiaryDetailPort;
import com.pikume.back.feed.application.port.out.RecordFeedClickPort;
import com.pikume.back.feed.application.port.out.RecordFeedClickPreferencePort;
import com.pikume.back.feed.domain.FeedClick;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeedClickService implements RecordFeedClickUseCase {

	private final LoadFeedDiaryDetailPort loadFeedDiaryDetailPort;
	private final LoadFeedClickHistoryPort loadFeedClickHistoryPort;
	private final RecordFeedClickPort recordFeedClickPort;
	private final RecordFeedClickPreferencePort recordFeedClickPreferencePort;

	@Override
	@Transactional
	public void recordClick(String userId, Long diaryId) {
		if (loadFeedDiaryDetailPort.loadVisibleDiary(diaryId, userId).isEmpty()) {
			return;
		}
		if (loadFeedClickHistoryPort.hasRecordedClick(userId, diaryId)) {
			return;
		}

		recordFeedClickPort.record(new FeedClick(userId, diaryId));
		updateUserPreferenceOnClick(userId, diaryId);
	}

	private void updateUserPreferenceOnClick(String userId, Long diaryId) {
		try {
			recordFeedClickPreferencePort.recordClickPreference(userId, diaryId);
			log.debug("event=feed_click_preference_updated outcome=success userId={} resourceId={}", userId, diaryId);
		} catch (Exception e) {
			log.warn("event=feed_click_preference_update_failed outcome=failed userId={} resourceId={} exception={}",
					userId, diaryId, e.getClass().getSimpleName());
		}
	}
}
