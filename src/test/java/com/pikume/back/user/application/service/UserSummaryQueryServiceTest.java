package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.AvatarCharacterReference;
import com.pikume.back.user.application.dto.AvatarCharacterSelection;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.dto.UserSummaryView;
import com.pikume.back.user.application.port.out.LoadUserReferencePort;
import com.pikume.back.user.application.port.out.ResolveAvatarCharacterReferencesPort;
import com.pikume.back.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSummaryQueryService")
class UserSummaryQueryServiceTest {

	@Mock
	private LoadUserReferencePort loadUserReferencePort;
	@Mock
	private ResolveAvatarCharacterReferencesPort resolveAvatarCharacterReferencesPort;

	@Test
	@DisplayName("프로필 설정 전 회원만 조회하면 아바타를 해석하지 않고 빈 요약을 반환한다")
	void hidesPendingSummaries() {
		User pending = User.pending("pending@test.com", "pw", "가입대기_pending", 1L);
		given(loadUserReferencePort.loadReferences(Set.of("pending"))).willReturn(List.of(pending));
		var service = new UserSummaryQueryService(
				loadUserReferencePort,
				new UserAvatarReferenceResolver(resolveAvatarCharacterReferencesPort));

		assertThat(service.queryUserSummaries(Set.of("pending"))).isEmpty();
		verifyNoInteractions(resolveAvatarCharacterReferencesPort);
	}

	@Test
	@DisplayName("혼합 목록에서는 완료 회원만 공개하고 프로필 완료 후 같은 회원을 포함한다")
	void includesPendingUserOnlyAfterCompletion() {
		User completed = new User("completed", "completed@test.com", "pw", "완료회원", 1L);
		User pending = User.pending("pending@test.com", "pw", "가입대기_pending", 2L);
		ReflectionTestUtils.setField(pending, "id", "pending");
		Set<String> ids = Set.of("completed", "pending");
		given(loadUserReferencePort.loadReferences(ids)).willReturn(List.of(completed, pending));
		var firstSelection = new AvatarCharacterSelection("completed", 1L);
		var secondSelection = new AvatarCharacterSelection("pending", 2L);
		var firstAvatar = new AvatarCharacterReference(
				"completed", 1L, new UserAvatarReference("first.webp", false, false));
		var secondAvatar = new AvatarCharacterReference(
				"pending", 2L, new UserAvatarReference("second.webp", false, false));
		given(resolveAvatarCharacterReferencesPort.resolveAvatarCharacterReferences(Set.of(firstSelection)))
				.willReturn(List.of(firstAvatar));
		var service = new UserSummaryQueryService(
				loadUserReferencePort,
				new UserAvatarReferenceResolver(resolveAvatarCharacterReferencesPort));

		assertThat(service.queryUserSummaries(ids)).containsOnlyKeys("completed");

		pending.completeProfile("새회원", 2L);
		given(resolveAvatarCharacterReferencesPort.resolveAvatarCharacterReferences(
				Set.of(firstSelection, secondSelection))).willReturn(List.of(firstAvatar, secondAvatar));
		Map<String, UserSummaryView> result = service.queryUserSummaries(ids);
		assertThat(result).containsOnlyKeys("completed", "pending");
		assertThat(result.get("pending").nickname()).isEqualTo("새회원");
	}

	@Test
	@DisplayName("여러 사용자 아바타 캐릭터를 한 번에 해석해 요약 View를 만든다")
	void resolvesAvatarCharactersInOneBatch() {
		User first = new User("user-1", "first@test.com", "pw", "첫째", 1L);
		User second = new User("user-2", "second@test.com", "pw", "둘째", 2L);
		given(loadUserReferencePort.loadReferences(Set.of("user-1", "user-2")))
				.willReturn(List.of(first, second));
		AvatarCharacterSelection firstSelection = new AvatarCharacterSelection("user-1", 1L);
		AvatarCharacterSelection secondSelection = new AvatarCharacterSelection("user-2", 2L);
		given(resolveAvatarCharacterReferencesPort.resolveAvatarCharacterReferences(
				Set.of(firstSelection, secondSelection)))
				.willReturn(List.of(
						new AvatarCharacterReference(
								"user-1", 1L,
								new UserAvatarReference("first.webp", false, false)),
						new AvatarCharacterReference(
								"user-2", 2L,
								new UserAvatarReference("second.webp", false, false))));
		UserSummaryQueryService service = new UserSummaryQueryService(
				loadUserReferencePort,
				new UserAvatarReferenceResolver(resolveAvatarCharacterReferencesPort));

		Map<String, UserSummaryView> result = service.queryUserSummaries(Set.of("user-1", "user-2"));

		assertThat(result).containsEntry(
				"user-1", new UserSummaryView(
						"user-1", "첫째", new UserAvatarReference("first.webp", false, false)));
		assertThat(result).containsEntry(
				"user-2", new UserSummaryView(
						"user-2", "둘째", new UserAvatarReference("second.webp", false, false)));
	}
}
