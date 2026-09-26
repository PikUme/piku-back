package com.pikume.back.character.application.service;

import com.pikume.back.character.application.dto.UsableCharacterReference;
import com.pikume.back.character.application.port.in.ResolveUsableCharacterReferenceUseCase;
import com.pikume.back.character.application.port.out.CanonicalizeFixedCharacterObjectKeyPort;
import com.pikume.back.character.application.port.out.LoadCharacterReferencePort;
import com.pikume.back.character.domain.Character;
import com.pikume.back.character.domain.vo.CharacterCreationType;
import com.pikume.back.character.domain.vo.CharacterImageReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class UsableCharacterReferenceService implements ResolveUsableCharacterReferenceUseCase {

	private final LoadCharacterReferencePort loadCharacterReferencePort;
	private final CanonicalizeFixedCharacterObjectKeyPort canonicalizeFixedCharacterObjectKeyPort;

	@Override
	public Optional<UsableCharacterReference> resolveUsableReference(
			String requestingUserId,
			Long characterId
	) {
		if (requestingUserId == null || requestingUserId.isBlank()
				|| characterId == null || characterId <= 0) {
			return Optional.empty();
		}
		return loadCharacterReferencePort.loadCharacterReference(characterId)
				.filter(character -> character.isAvailableTo(requestingUserId))
				.flatMap(character -> normalizeReference(characterId, character))
				.map(UsableCharacterReference::new);
	}

	private Optional<String> normalizeReference(Long characterId, Character character) {
		if (character.getType() == CharacterCreationType.FIXED) {
			return normalizeFixedReference(characterId, character.getImageReference());
		}
		return normalizeAiGeneratedReference(characterId, character.getImageReference());
	}

	private Optional<String> normalizeAiGeneratedReference(Long characterId, String storedReference) {
		try {
			return CharacterImageReference.of(storedReference).toUsableObjectKey();
		} catch (IllegalArgumentException exception) {
			log.warn("event=character_image_reference_unavailable outcome=skipped resourceId={} exception={}",
					characterId,
					exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private Optional<String> normalizeFixedReference(Long characterId, String storedReference) {
		try {
			String normalized = canonicalizeFixedCharacterObjectKeyPort
					.canonicalizeFixedCharacterObjectKey(storedReference);
			return CharacterImageReference.of(normalized).toUsableObjectKey();
		} catch (IllegalArgumentException exception) {
			log.warn("event=character_image_reference_unavailable outcome=skipped resourceId={} exception={}",
					characterId,
					exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
