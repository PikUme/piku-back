package com.pikume.back.creative.application.service;

import com.pikume.back.creative.application.port.in.RecordAiPhotoStatisticsUseCase;
import com.pikume.back.creative.application.port.out.RecordAiPhotoStatisticsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiPhotoStatisticsService implements RecordAiPhotoStatisticsUseCase {

	private final RecordAiPhotoStatisticsPort recordAiPhotoStatisticsPort;

	@Override
	public void recordRequest(String userId) {
		recordSafely("request", userId, () -> recordAiPhotoStatisticsPort.recordRequest(userId));
	}

	@Override
	public void recordSuccess(String userId) {
		recordSafely("success", userId, () -> recordAiPhotoStatisticsPort.recordSuccess(userId));
	}

	@Override
	public void recordFailure(String userId) {
		recordSafely("failure", userId, () -> recordAiPhotoStatisticsPort.recordFailure(userId));
	}

	private void recordSafely(String eventType, String userId, Runnable recorder) {
		try {
			recorder.run();
		} catch (RuntimeException e) {
			log.warn("event=ai_photo_statistics_record_failed outcome=failed eventType={} userId={} exception={}",
					eventType,
					userId,
					e.getClass().getSimpleName());
		}
	}
}
