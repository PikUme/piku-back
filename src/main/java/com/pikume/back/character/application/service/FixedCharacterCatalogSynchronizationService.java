package com.pikume.back.character.application.service;

import com.pikume.back.character.application.port.in.SynchronizeFixedCharacterCatalogUseCase;
import com.pikume.back.character.application.port.out.CanonicalizeFixedCharacterObjectKeyPort;
import com.pikume.back.character.application.port.out.LoadFixedCharacterAssetsPort;
import com.pikume.back.character.application.port.out.LoadFixedCharactersPort;
import com.pikume.back.character.application.port.out.RecordFixedCharacterPort;
import com.pikume.back.character.domain.Character;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class FixedCharacterCatalogSynchronizationService implements SynchronizeFixedCharacterCatalogUseCase {

	private final LoadFixedCharactersPort loadFixedCharactersPort;
	private final RecordFixedCharacterPort recordFixedCharacterPort;
	private final LoadFixedCharacterAssetsPort loadFixedCharacterAssetsPort;
	private final CanonicalizeFixedCharacterObjectKeyPort canonicalizeFixedCharacterObjectKeyPort;

	@Override
	@Transactional
	public int synchronizeFixedCharacterCatalog() {
		Set<String> existingObjectKeys = loadFixedCharactersPort.loadFixedCharacters().stream()
				.map(Character::getImageReference)
				.map(this::canonicalizeSafely)
				.flatMap(Optional::stream)
				.collect(Collectors.toSet());
		List<String> storageObjectKeys = loadCatalogSafely();
		if (storageObjectKeys.isEmpty()) {
			return 0;
		}

		int recorded = 0;
		for (String objectKey : storageObjectKeys) {
			if (existingObjectKeys.add(objectKey)) {
				recordFixedCharacterPort.recordFixedCharacter(Character.fixed(objectKey));
				recorded++;
			}
		}
		return recorded;
	}

	private List<String> loadCatalogSafely() {
		try {
			return loadFixedCharacterAssetsPort.loadFixedCharacterObjectKeys().stream()
					.map(this::canonicalizeSafely)
					.flatMap(Optional::stream)
					.distinct()
					.toList();
		} catch (RuntimeException exception) {
			log.warn("event=fixed_character_catalog_load_failed outcome=unchanged exception={}",
					exception.getClass().getSimpleName());
			return List.of();
		}
	}

	private Optional<String> canonicalizeSafely(String reference) {
		try {
			String objectKey = canonicalizeFixedCharacterObjectKeyPort.canonicalizeFixedCharacterObjectKey(reference);
			return objectKey.isBlank() ? Optional.empty() : Optional.of(objectKey);
		} catch (IllegalArgumentException exception) {
			log.warn("event=fixed_character_reference_invalid outcome=skipped exception={}", exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
