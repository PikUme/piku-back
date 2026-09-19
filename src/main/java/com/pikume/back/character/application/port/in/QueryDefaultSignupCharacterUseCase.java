package com.pikume.back.character.application.port.in;

import java.util.Optional;

public interface QueryDefaultSignupCharacterUseCase {
    Optional<Long> queryDefaultSignupCharacterId();
}
