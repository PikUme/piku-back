package com.pikume.back.user.auth.adapter.out.character;
import com.pikume.back.character.application.port.in.QueryDefaultSignupCharacterUseCase;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.out.ResolveDefaultSignupCharacterPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class DefaultSignupCharacterAdapter implements ResolveDefaultSignupCharacterPort {
    private final QueryDefaultSignupCharacterUseCase characters;
    public Long resolveDefaultSignupCharacter() {
        return characters.queryDefaultSignupCharacterId().orElseThrow(() -> new SignupFlowException(SignupFailure.DEFAULT_CHARACTER_UNAVAILABLE));
    }
}
