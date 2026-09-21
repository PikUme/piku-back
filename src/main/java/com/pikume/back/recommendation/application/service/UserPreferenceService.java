package com.pikume.back.recommendation.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pikume.back.recommendation.application.port.in.QueryUserTopicAffinitiesUseCase;
import com.pikume.back.recommendation.application.port.in.RecordTopicInteractionUseCase;
import com.pikume.back.recommendation.application.port.out.LoadUserPreferencePort;
import com.pikume.back.recommendation.application.port.out.RecordUserPreferencePort;
import com.pikume.back.recommendation.application.port.out.RecommendationClockPort;
import com.pikume.back.recommendation.domain.InteractionType;
import com.pikume.back.recommendation.domain.UserPreference;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserPreferenceService
		implements RecordTopicInteractionUseCase, QueryUserTopicAffinitiesUseCase {

	private final LoadUserPreferencePort loadUserPreferencePort;
	private final RecordUserPreferencePort recordUserPreferencePort;
	private final RecommendationClockPort recommendationClockPort;

	@Override
	@Transactional
	public void recordClick(String userId, String topic) {
		recordInteraction(userId, topic, InteractionType.CLICK);
	}

	@Override
	@Transactional
	public void recordLike(String userId, String topic) {
		recordInteraction(userId, topic, InteractionType.LIKE);
	}

	@Override
	@Transactional
	public void recordView(String userId, String topic) {
		recordInteraction(userId, topic, InteractionType.VIEW);
	}

	@Override
	@Transactional
	public void recordOther(String userId, String topic) {
		recordInteraction(userId, topic, InteractionType.OTHER);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, Double> queryUserTopicAffinities(String userId) {
		if (userId == null) {
			return Map.of();
		}

		return loadUserPreferencePort.loadByUserId(userId)
				.map(UserPreference::getTopicAffinities)
				.map(affinities -> affinities.values())
				.orElse(Map.of());
	}

	private void recordInteraction(String userId, String topic, InteractionType interactionType) {
		UserPreference preference = loadUserPreferencePort.loadByUserId(userId)
				.orElseGet(() -> recordUserPreferencePort.recordUserPreference(
						UserPreference.create(userId, recommendationClockPort.now())));
		preference.recordInteraction(topic, interactionType, recommendationClockPort.now());
		log.debug("event=topic_interaction_recorded outcome=success userId={} interactionType={} weight={}",
				userId, interactionType, interactionType.weight());
	}
}
