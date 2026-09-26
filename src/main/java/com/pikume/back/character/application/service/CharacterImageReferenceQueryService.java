package com.pikume.back.character.application.service;

import com.pikume.back.character.application.dto.CharacterImageReferenceQuery;
import com.pikume.back.character.application.dto.CharacterImageReferenceResult;
import com.pikume.back.character.application.port.in.QueryCharacterImageReferencesUseCase;
import com.pikume.back.character.application.port.out.CanonicalizeFixedCharacterObjectKeyPort;
import com.pikume.back.character.application.port.out.LoadCharacterReferencePort;
import com.pikume.back.character.domain.Character;
import com.pikume.back.character.domain.vo.CharacterCreationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CharacterImageReferenceQueryService implements QueryCharacterImageReferencesUseCase {

	private final LoadCharacterReferencePort loadCharacterReferencePort;
	private final CanonicalizeFixedCharacterObjectKeyPort canonicalizeFixedCharacterObjectKeyPort;

	@Override
	public List<CharacterImageReferenceResult> queryCharacterImageReferences(
			Collection<CharacterImageReferenceQuery> queries) {
		Set<CharacterImageReferenceQuery> requestedQueries = validDistinctQueries(queries);
		if (requestedQueries.isEmpty()) {
			return List.of();
		}
		Set<Long> requestedIds = requestedQueries.stream()
				.map(CharacterImageReferenceQuery::characterId)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		Map<Long, Character> charactersById = loadCharacterReferencePort.loadCharacterReferences(requestedIds)
				.stream()
				.collect(Collectors.toMap(Character::getId, Function.identity()));

		return requestedQueries.stream()
				.map(query -> toResult(query, charactersById.get(query.characterId())))
				.flatMap(Optional::stream)
				.toList();
	}

	private Set<CharacterImageReferenceQuery> validDistinctQueries(
			Collection<CharacterImageReferenceQuery> queries) {
		if (queries == null || queries.isEmpty()) {
			return Set.of();
		}
		return queries.stream()
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Optional<CharacterImageReferenceResult> toResult(
			CharacterImageReferenceQuery query,
			Character character) {
		if (character == null || !isAccessibleTo(query.userId(), character)) {
			return Optional.empty();
		}
		if (character.getType() == CharacterCreationType.AI_GENERATED) {
			String imageReference = character.getImageReference();
			return Optional.of(new CharacterImageReferenceResult(
					query.userId(),
					character.getId(),
					imageReference,
					isAbsoluteUrl(imageReference),
					false));
		}

		try {
			String objectKey = canonicalizeFixedCharacterObjectKeyPort
					.canonicalizeFixedCharacterObjectKey(character.getImageReference());
			return objectKey.isBlank()
					? Optional.empty()
					: Optional.of(new CharacterImageReferenceResult(
							query.userId(),
							character.getId(),
							objectKey,
							isAbsoluteUrl(objectKey),
							!isAbsoluteUrl(objectKey)));
		} catch (IllegalArgumentException exception) {
			log.warn("event=fixed_character_reference_invalid outcome=skipped resourceId={} exception={}",
					character.getId(), exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private boolean isAccessibleTo(String userId, Character character) {
		return character.getType() == CharacterCreationType.FIXED
				|| userId.equals(character.getUserId());
	}

	private boolean isAbsoluteUrl(String imageReference) {
		return imageReference.startsWith("http://") || imageReference.startsWith("https://");
	}
}
