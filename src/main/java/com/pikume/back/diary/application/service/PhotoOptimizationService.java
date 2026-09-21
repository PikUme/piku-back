package com.pikume.back.diary.application.service;

import com.pikume.back.diary.application.dto.PhotoOptimizationTarget;
import com.pikume.back.diary.application.port.out.LoadDiaryPhotoObjectPort;
import com.pikume.back.diary.application.port.out.LoadPhotoOptimizationPort;
import com.pikume.back.diary.application.port.out.SavePhotoOptimizationPort;
import com.pikume.back.diary.application.port.out.StoreOptimizedDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.WebpImageConversionPort;
import com.pikume.back.diary.domain.PhotoOptimizationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoOptimizationService {

	private static final String WEBP_CONTENT_TYPE = "image/webp";

	private final LoadPhotoOptimizationPort loadPhotoOptimizationPort;
	private final SavePhotoOptimizationPort savePhotoOptimizationPort;
	private final LoadDiaryPhotoObjectPort loadObjectPort;
	private final StoreOptimizedDiaryPhotoPort storeObjectPort;
	private final WebpImageConversionPort webpImageConversionPort;
	private final PhotoOptimizationProperties properties;

	public int optimizePendingPhotos() {
		List<PhotoOptimizationTarget> targets = loadPhotoOptimizationPort.findPendingPhotoOptimizationTargets(
				properties.getBatchSize());
		int optimizedCount = 0;

		for (PhotoOptimizationTarget target : targets) {
			if (optimizeTarget(target)) {
				optimizedCount++;
			}
		}

		return optimizedCount;
	}

	private boolean optimizeTarget(PhotoOptimizationTarget target) {
		LocalDateTime attemptedAt = LocalDateTime.now();
		if (!savePhotoOptimizationPort.claimPhotoOptimization(target.photoId(), attemptedAt)) {
			log.debug("event=photo_optimization_claim outcome=skipped photoId={} diaryId={}",
					target.photoId(),
					target.diaryId());
			return false;
		}

		try {
			String optimizedKey = PhotoWebpObjectKey.fromOriginal(target.originalUrl()).orElse(null);
			if (optimizedKey == null) {
				savePhotoOptimizationPort.markPhotoOptimizationSkipped(target.photoId(), LocalDateTime.now());
				log.debug("event=photo_optimization outcome=skipped photoId={} diaryId={}",
						target.photoId(),
						target.diaryId());
				return false;
			}
			byte[] originalBytes = loadObjectPort.load(target.originalUrl());
			byte[] webpBytes = webpImageConversionPort.convertToWebp(originalBytes, properties.getQuality());

			storeObjectPort.store(optimizedKey, WEBP_CONTENT_TYPE, webpBytes);
			savePhotoOptimizationPort.markPhotoOptimizationSucceeded(target.photoId(), optimizedKey, LocalDateTime.now());
			log.info("event=photo_optimization outcome=succeeded photoId={} diaryId={}",
					target.photoId(),
					target.diaryId());
			return true;
		} catch (Exception e) {
			PhotoOptimizationStatus nextStatus = nextFailureStatus(target);
			savePhotoOptimizationPort.markPhotoOptimizationFailed(target.photoId(), nextStatus, LocalDateTime.now());
			log.warn("event=photo_optimization outcome=failed photoId={} diaryId={} status={} attemptCount={} exception={}",
					target.photoId(),
					target.diaryId(),
					nextStatus,
					target.attemptCount() + 1,
					e.getClass().getSimpleName());
			return false;
		}
	}

	private PhotoOptimizationStatus nextFailureStatus(PhotoOptimizationTarget target) {
		int nextAttemptCount = target.attemptCount() + 1;
		if (nextAttemptCount >= properties.getMaxAttempts()) {
			return PhotoOptimizationStatus.FAILED;
		}
		return PhotoOptimizationStatus.PENDING;
	}

}
