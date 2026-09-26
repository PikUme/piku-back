package com.pikume.back.creative.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pikume.back.creative.application.dto.DiaryIllustrationRequest;
import com.pikume.back.creative.application.dto.AiGenerationQuotaConsumption;
import com.pikume.back.creative.application.dto.GeneratedImageResult;
import com.pikume.back.creative.application.dto.GeneratedIllustrationPayload;
import com.pikume.back.creative.application.dto.GenerateDiaryImageCommand;
import com.pikume.back.creative.application.exception.AiGenerationQuotaExceededException;
import com.pikume.back.creative.application.exception.CreativeErrorCode;
import com.pikume.back.creative.application.exception.CreativeException;
import com.pikume.back.creative.application.policy.DiaryIllustrationPromptPolicy;
import com.pikume.back.creative.application.port.in.GenerateImageUseCase;
import com.pikume.back.creative.application.port.in.ConsumeAiGenerationQuotaUseCase;
import com.pikume.back.creative.application.port.in.PrepareCharacterReferenceUseCase;
import com.pikume.back.creative.application.port.in.RecordAiPhotoStatisticsUseCase;
import com.pikume.back.creative.application.port.out.CreativeImageStoragePort;
import com.pikume.back.creative.application.port.out.GenerateDiaryIllustrationPort;
import com.pikume.back.creative.application.port.out.RecordGenerationPort;
import com.pikume.back.creative.domain.DiaryImageGeneration;

/**
 * AI 이미지 생성 Application Service
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageGenerationService implements GenerateImageUseCase {

	private final GenerateDiaryIllustrationPort generateDiaryIllustrationPort;
	private final RecordGenerationPort recordGenerationPort;
	private final PrepareCharacterReferenceUseCase prepareCharacterReferenceUseCase;
	private final CreativeImageStoragePort creativeImageStoragePort;
	private final DiaryIllustrationPromptPolicy diaryIllustrationPromptPolicy;
	private final ConsumeAiGenerationQuotaUseCase consumeAiGenerationQuotaUseCase;
	private final RecordAiPhotoStatisticsUseCase recordAiPhotoStatisticsUseCase;

	@Override
	@Transactional
	public GeneratedImageResult generateDiaryImage(GenerateDiaryImageCommand command) {
		String content = command.content();
		String userId = command.userId();
		log.debug("event=diary_image_generation_requested userId={}", userId);
		recordAiPhotoStatisticsUseCase.recordRequest(userId);

		AiGenerationQuotaConsumption consumption = consumeAiGenerationQuotaUseCase.tryConsumeForGeneration(userId);
		if (!consumption.consumed()) {
			throw new AiGenerationQuotaExceededException(consumption.dailyLimit());
		}

		GeneratedImageResult result;
		try {
			String characterImageBase64 = prepareCharacterReferenceUseCase
					.prepareCharacterReference(userId, command.characterId())
					.map(reference -> reference.imageBase64())
					.orElseThrow(() -> new CreativeException(
							CreativeErrorCode.CHARACTER_REFERENCE_UNAVAILABLE));

			String prompt = diaryIllustrationPromptPolicy.createPrompt(content);

			GeneratedIllustrationPayload illustration = generateDiaryIllustrationPort.generate(
					new DiaryIllustrationRequest(prompt, characterImageBase64));
			String generatedImageRelativePath = creativeImageStoragePort.storeGeneratedImage(
					illustration.imageBase64(),
					userId,
					illustration.fileExtension());

			String aiUrl = creativeImageStoragePort.resolveGeneratedImageUrl(generatedImageRelativePath, false);
			DiaryImageGeneration diaryImageGeneration = recordGenerationPort.recordGeneration(
					DiaryImageGeneration.create(userId, prompt, generatedImageRelativePath));
			log.info("event=diary_image_generated outcome=success userId={} resourceId={}",
					userId, diaryImageGeneration.getId());
			result = new GeneratedImageResult(diaryImageGeneration.getId(), aiUrl, generatedImageRelativePath);
		} catch (RuntimeException e) {
			releaseConsumptionSafely(userId, e);
			recordAiPhotoStatisticsUseCase.recordFailure(userId);
			throw e;
		}

		recordAiPhotoStatisticsUseCase.recordSuccess(userId);
		return result;
	}

	private void releaseConsumptionSafely(String userId, RuntimeException cause) {
		try {
			consumeAiGenerationQuotaUseCase.releaseGenerationConsumption(userId);
		} catch (RuntimeException releaseFailure) {
			log.warn("event=ai_generation_quota_release_failed outcome=failed userId={} originalFailure={} releaseFailure={}",
					userId,
					cause.getClass().getSimpleName(),
					releaseFailure.getClass().getSimpleName());
		}
	}
}
