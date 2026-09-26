package com.pikume.back.character.application.service;

import com.pikume.back.character.application.port.in.QueryDefaultSignupCharacterUseCase;
import com.pikume.back.character.application.port.out.CanonicalizeFixedCharacterObjectKeyPort;
import com.pikume.back.character.application.port.out.LoadFixedCharactersPort;
import com.pikume.back.character.domain.vo.CharacterCreationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Optional;

@Service @RequiredArgsConstructor
public class DefaultSignupCharacterQueryService implements QueryDefaultSignupCharacterUseCase {
    private final LoadFixedCharactersPort characters;
    private final CanonicalizeFixedCharacterObjectKeyPort keys;

    @Override @Transactional(readOnly=true)
    public Optional<Long> queryDefaultSignupCharacterId() {
        var matches=new ArrayList<Long>();
        for (var character:characters.loadFixedCharacters()) {
            if (character.getType()!=CharacterCreationType.FIXED || character.getUserId()!=null)continue;
            String key;
            try {
                key=keys.canonicalizeFixedCharacterObjectKey(character.getImageReference());
            } catch (IllegalArgumentException unusable) {
                continue;
            }
            if (com.pikume.back.character.domain.vo.CharacterImageReference.of(key).toUsableObjectKey().isEmpty()) continue;
            if ((key.equals("base_image_1.webp") || key.endsWith("/base_image_1.webp")) && character.getId()!=null && character.getId()>0)
            matches.add(character.getId());
        }
        return matches.size()==1?Optional.of(matches.get(0)):Optional.empty();
    }
}
