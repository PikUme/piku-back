package com.pikume.back.character.application.service;

import com.pikume.back.character.application.port.in.GetCharacterUseCase;
import com.pikume.back.character.application.port.in.ResolveFixedCharacterReferenceUseCase;
import com.pikume.back.character.application.port.out.CanonicalizeFixedCharacterObjectKeyPort;
import com.pikume.back.character.application.port.out.LoadCharacterReferencePort;
import com.pikume.back.character.domain.vo.CharacterCreationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class FixedCharacterReferenceService
		implements ResolveFixedCharacterReferenceUseCase, GetCharacterUseCase {

	private final LoadCharacterReferencePort loadCharacterReferencePort;
	private final CanonicalizeFixedCharacterObjectKeyPort canonicalizeFixedCharacterObjectKeyPort;

	@Override
	public Optional<String> resolveFixedCharacterObjectKey(Long characterId) {
		if (characterId == null || characterId <= 0) {
			return Optional.empty();
		}
		return loadCharacterReferencePort.loadCharacterReference(characterId)
				.filter(character -> character.getType() == CharacterCreationType.FIXED)
				.flatMap(character -> canonicalize(characterId, character.getImageReference()));
	}

	@Override
	public Optional<String> findFixedCharacterObjectKey(Long characterId) {
		return resolveFixedCharacterObjectKey(characterId);
	}

	private Optional<String> canonicalize(Long characterId, String storedReference) {
		try {
			String objectKey = canonicalizeFixedCharacterObjectKeyPort.canonicalizeFixedCharacterObjectKey(storedReference);
			return objectKey.isBlank() ? Optional.empty() : Optional.of(objectKey);
		} catch (IllegalArgumentException exception) {
			log.warn("event=fixed_character_reference_invalid outcome=skipped resourceId={} exception={}",
					characterId,
					exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
