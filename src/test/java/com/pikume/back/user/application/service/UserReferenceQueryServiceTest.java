package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.AvatarCharacterReference;
import com.pikume.back.user.application.dto.AvatarCharacterSelection;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.port.out.LoadUserReferencePort;
import com.pikume.back.user.application.port.out.ResolveAvatarCharacterReferencesPort;
import com.pikume.back.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserReferenceQueryService")
class UserReferenceQueryServiceTest {

	@Mock
	private LoadUserReferencePort loadUserReferencePort;
	@Mock
	private ResolveAvatarCharacterReferencesPort resolveAvatarCharacterReferencesPort;

	@Test
	@DisplayName("프로필 설정 전 회원은 다른 Context의 공개 참조 대상에서 제외한다")
	void hidesPendingReference() {
		User user = User.pending("pending@test.com", "pw", "가입대기_pending", 1L);
		given(loadUserReferencePort.loadReference("pending")).willReturn(Optional.of(user));
		var service = new UserReferenceQueryService(
				loadUserReferencePort,
				new UserAvatarReferenceResolver(resolveAvatarCharacterReferencesPort));

		assertThat(service.queryUserReference("pending")).isEmpty();
		verifyNoInteractions(resolveAvatarCharacterReferencesPort);
	}

	@Test
	@DisplayName("사용자 Aggregate를 외부 Context용 공개 참조 View로 변환한다")
	void mapsUserToPublicReferenceView() {
		User user = new User("user-1", "user@example.com", "password", "pikume", 1L);
		given(loadUserReferencePort.loadReference("user-1")).willReturn(Optional.of(user));
		given(resolveAvatarCharacterReferencesPort.resolveAvatarCharacterReferences(
				java.util.Set.of(new AvatarCharacterSelection("user-1", 1L))))
				.willReturn(List.of(new AvatarCharacterReference(
						"user-1", 1L,
						new UserAvatarReference("avatar-key", false, false))));

		var result = new UserReferenceQueryService(
				loadUserReferencePort,
				new UserAvatarReferenceResolver(resolveAvatarCharacterReferencesPort))
				.queryUserReference("user-1");

		assertThat(result).contains(new com.pikume.back.user.application.dto.UserReferenceView(
				"user-1", "pikume", new UserAvatarReference("avatar-key", false, false)));
	}
}
