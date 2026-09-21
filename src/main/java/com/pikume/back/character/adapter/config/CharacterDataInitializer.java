package com.pikume.back.character.adapter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.pikume.back.character.application.port.in.SynchronizeFixedCharacterCatalogUseCase;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 고정 캐릭터 데이터 초기화
 * Application use case를 통해 MinIO fixed character catalog와 DB를 동기화합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterDataInitializer implements CommandLineRunner {

	private final SynchronizeFixedCharacterCatalogUseCase synchronizeFixedCharacterCatalogUseCase;

	@Override
	public void run(String... args) throws Exception {
		int newCharactersAdded = synchronizeFixedCharacterCatalogUseCase.synchronizeFixedCharacterCatalog();

		if (newCharactersAdded > 0) {
			log.info("event=fixed_character_catalog_synchronized outcome=success addedCount={}", newCharactersAdded);
		} else {
			log.debug("event=fixed_character_catalog_synchronized outcome=unchanged addedCount=0");
		}
	}
}
