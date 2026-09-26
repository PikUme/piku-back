package com.pikume.back.character.application.service;

import com.pikume.back.character.application.dto.CharacterResult;
import com.pikume.back.character.application.exception.CharacterErrorCode;
import com.pikume.back.character.application.exception.CharacterException;
import com.pikume.back.character.application.port.in.QueryFixedCharacterCatalogUseCase;
import com.pikume.back.character.application.port.out.CanonicalizeFixedCharacterObjectKeyPort;
import com.pikume.back.character.application.port.out.LoadFixedCharactersPort;
import com.pikume.back.character.domain.Character;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class FixedCharacterCatalogQueryService implements QueryFixedCharacterCatalogUseCase {

	private final LoadFixedCharactersPort loadFixedCharactersPort;
	private final CanonicalizeFixedCharacterObjectKeyPort canonicalizeFixedCharacterObjectKeyPort;

	@Override
	public List<CharacterResult> queryFixedCharacters() {
		List<Character> fixedCharacters;
		try {
			fixedCharacters = loadFixedCharactersPort.loadFixedCharacters();
		} catch (RuntimeException exception) {
			throw new CharacterException(CharacterErrorCode.CATALOG_UNAVAILABLE, exception);
		}

		Map<String, CharacterResult> selectedByBaseName = new LinkedHashMap<>();
		for (Character character : fixedCharacters) {
			toCanonicalResult(character).ifPresent(result -> {
				String baseName = removePreferredExtension(result.imageReference());
				CharacterResult selected = selectedByBaseName.get(baseName);
				if (selected == null || result.imageReference().endsWith(".webp")) {
					selectedByBaseName.put(baseName, result);
				}
			});
		}
		return List.copyOf(selectedByBaseName.values());
	}

	private Optional<CharacterResult> toCanonicalResult(Character character) {
		try {
			String objectKey = canonicalizeFixedCharacterObjectKeyPort.canonicalizeFixedCharacterObjectKey(
					character.getImageReference());
			if (objectKey.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(new CharacterResult(
					character.getId(),
					character.getUserId(),
					objectKey,
					character.getType()));
		} catch (IllegalArgumentException exception) {
			log.warn("event=fixed_character_reference_invalid outcome=skipped resourceId={} exception={}",
					character.getId(), exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private String removePreferredExtension(String reference) {
		if (reference.endsWith(".webp")) {
			return reference.substring(0, reference.length() - ".webp".length());
		}
		if (reference.endsWith(".png")) {
			return reference.substring(0, reference.length() - ".png".length());
		}
		return reference;
	}
}
