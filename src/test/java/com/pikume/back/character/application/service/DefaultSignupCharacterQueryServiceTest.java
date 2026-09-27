package com.pikume.back.character.application.service;

import com.pikume.back.character.application.port.out.*;
import com.pikume.back.character.domain.Character;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultSignupCharacterQueryServiceTest {
    final LoadFixedCharactersPort catalog=mock(LoadFixedCharactersPort.class);
    final CanonicalizeFixedCharacterObjectKeyPort keys=mock(CanonicalizeFixedCharacterObjectKeyPort.class);
    final DefaultSignupCharacterQueryService service=new DefaultSignupCharacterQueryService(catalog,keys);
    DefaultSignupCharacterQueryServiceTest() {
        when(keys.canonicalizeFixedCharacterObjectKey(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    Character fixed(long id,String key) {
        Character c=Character.fixed(key);ReflectionTestUtils.setField(c,"id",id);
        return c;
    }
    @Test void returnsActualPersistedIdentifierForExactlyOneDefault() {
        when(catalog.loadFixedCharacters()).thenReturn(List.of(fixed(37,"public/characters/fixed/base_image_1.webp"),fixed(99,"public/characters/fixed/base_image_2.webp")));
        assertThat(service.queryDefaultSignupCharacterId()).contains(37L);
    }
    @Test void ambiguousRowsAreNotSilentlyDeduplicated() {
        when(catalog.loadFixedCharacters()).thenReturn(List.of(fixed(37,"public/characters/fixed/base_image_1.webp"),fixed(42,"public/characters/fixed/base_image_1.webp")));
        assertThat(service.queryDefaultSignupCharacterId()).isEmpty();
    }
    @Test void absoluteUrlAndMissingDefaultAreUnusable() {
        when(catalog.loadFixedCharacters()).thenReturn(List.of(fixed(37,"https://example.com/base_image_1.webp"),fixed(99,"base_image_2.webp")));
        assertThat(service.queryDefaultSignupCharacterId()).isEmpty();
    }
}
