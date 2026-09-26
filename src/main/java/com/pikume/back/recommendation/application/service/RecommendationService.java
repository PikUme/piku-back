package com.pikume.back.recommendation.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pikume.back.recommendation.application.dto.RecommendationScoreResult;
import com.pikume.back.recommendation.application.port.in.QueryUserTopicAffinitiesUseCase;
import com.pikume.back.recommendation.application.port.in.ScoreDiaryCandidatesUseCase;
import com.pikume.back.recommendation.application.port.out.LoadDiaryMetadataPort;
import com.pikume.back.recommendation.application.port.out.RecommendationClockPort;
import com.pikume.back.recommendation.domain.DiaryMetadata;
import com.pikume.back.recommendation.domain.RecommendationScore;
import com.pikume.back.recommendation.domain.TopicAffinities;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationService implements ScoreDiaryCandidatesUseCase {

	private final LoadDiaryMetadataPort loadDiaryMetadataPort;
	private final QueryUserTopicAffinitiesUseCase queryUserTopicAffinitiesUseCase;
	private final RecommendationClockPort recommendationClockPort;

	public double calculateScore(DiaryMetadata metadata, Map<String, Double> userAffinities) {
		return RecommendationScore.calculate(
				metadata,
				TopicAffinities.from(userAffinities),
				recommendationClockPort.now()).value();
	}

	public List<RecommendationScoreResult> scoreAndSort(List<DiaryMetadata> metadataList,
			Map<String, Double> userAffinities) {
		return metadataList.stream()
				.map(metadata -> new RecommendationScoreResult(
						metadata.getDiaryId(),
						calculateScore(metadata, userAffinities)))
				.sorted((a, b) -> Double.compare(b.score(), a.score()))
				.collect(Collectors.toList());
	}

	@Override
	@Transactional(readOnly = true)
	public List<RecommendationScoreResult> scoreDiaryCandidates(String userId, List<Long> candidateDiaryIds) {
		if (candidateDiaryIds == null || candidateDiaryIds.isEmpty()) {
			return Collections.emptyList();
		}

		List<DiaryMetadata> metadataList = loadDiaryMetadataPort.loadByDiaryIds(candidateDiaryIds);
		Map<Long, DiaryMetadata> metadataMap = metadataList.stream()
				.collect(Collectors.toMap(DiaryMetadata::getDiaryId, m -> m));

		Map<String, Double> userAffinities =
				queryUserTopicAffinitiesUseCase.queryUserTopicAffinities(userId);

		List<RecommendationScoreResult> results = candidateDiaryIds.stream()
				.map(diaryId -> {
					DiaryMetadata metadata = metadataMap.get(diaryId);
					double score = metadata != null
							? calculateScore(metadata, userAffinities)
							: RecommendationScore.withoutMetadata().value();
					return new RecommendationScoreResult(diaryId, score);
				})
				.sorted((a, b) -> Double.compare(b.score(), a.score()))
				.collect(Collectors.toList());

		log.debug("event=recommendation_scored outcome=success candidateCount={} metadataCount={}", candidateDiaryIds.size(), metadataMap.size());
		return results;
	}
}
