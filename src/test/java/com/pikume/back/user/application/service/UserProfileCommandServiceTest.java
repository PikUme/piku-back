package com.pikume.back.user.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.pikume.back.user.application.port.out.ResolveFixedCharacterAvatarPort;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForProfilePort;
import com.pikume.back.user.application.port.out.NicknameHoldPort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.application.dto.UpdateProfileCommand;
import com.pikume.back.user.application.dto.UpdateProfileFailureReason;
import com.pikume.back.user.application.dto.UpdateProfileResult;
import com.pikume.back.user.application.exception.ProfileImageNotFoundException;
import com.pikume.back.user.application.exception.UserErrorCode;
import com.pikume.back.user.application.exception.UserNotFoundException;
import com.pikume.back.user.adapter.out.memory.InMemoryNicknameHoldAdapter;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.InvalidNicknameException;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.service.NicknamePolicy;
import com.pikume.back.user.domain.vo.Nickname;

import java.util.Optional;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileCommandService")
class UserProfileCommandServiceTest {

	@InjectMocks
	private UserProfileCommandService service;

	@Mock
	private LoadUserForProfilePort loadUserForProfilePort;
	@Mock
	private RecordUserAccountPort recordUserAccountPort;
	@Mock
	private CheckUserUniquenessPort checkUserUniquenessPort;
	@Mock
	private NicknameHoldPort nicknameHoldPort;
	@Mock
	private ResolveFixedCharacterAvatarPort fixedCharacterAvatarPort;
	@Mock
	private NicknamePolicy nicknamePolicy;

	@Nested
	@DisplayName("reserveIfAvailable - 닉네임 사용 가능 확인")
	class CheckAvailability {

		@Test
		@DisplayName("정규화 후 현재 자신의 닉네임이면 새 점유 없이 사용 가능하다")
		void ownNicknameIsAvailable() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));

			boolean result = service.reserveIfAvailable(" \u2003현재닉\u3000 ", "user-1");

			assertThat(result).isTrue();
			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(nicknameHoldPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("유효하지 않은 닉네임은 사용자를 조회하기 전에 거절한다")
		void invalidNicknameIsRejectedBeforeLoadingUser() {
			assertThatThrownBy(() -> service.reserveIfAvailable(" \u2003\u3000 ", "user-1"))
					.isInstanceOf(InvalidNicknameException.class);

			then(loadUserForProfilePort).shouldHaveNoInteractions();
			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(nicknameHoldPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 DB에 존재하는 닉네임이면 사용 불가")
		void existingNicknameIsUnavailable() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(checkUserUniquenessPort.isNicknameInUse(new Nickname("중복닉"))).willReturn(true);

			boolean result = service.reserveIfAvailable(" \u2003중복닉\u3000 ", "user-1");

			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("사용 가능한 닉네임 점유를 목적 중심 Port에 위임한다")
		void delegatesNicknameAcquisitionToHoldPort() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(nicknameHoldPort.tryAcquire(eq(new Nickname("새닉")), eq("user-1"), any(Instant.class))).willReturn(true);

			assertThat(service.reserveIfAvailable(" \u2003새닉\u3000 ", "user-1")).isTrue();
		}

		@Test
		@DisplayName("존재하지 않는 사용자면 예외 발생")
		void nonExistentUserThrows() {
			given(loadUserForProfilePort.loadProfileUser("unknown")).willReturn(Optional.empty());

				assertThatThrownBy(() -> service.reserveIfAvailable("닉네임", "unknown"))
						.isInstanceOfSatisfying(UserNotFoundException.class,
								ex -> assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND));
		}
	}

	@Nested
	@DisplayName("updateProfile - 프로필 변경")
	class UpdateProfile {

		@Test
		@DisplayName("닉네임과 캐릭터 모두 없으면 실패 응답")
		void noChangesReturnFailure() {
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", null, null);

			UpdateProfileResult result = service.updateProfile(command);

			assertThat(result.success()).isFalse();
			assertThat(result.failureReason()).isEqualTo(UpdateProfileFailureReason.INVALID_REQUEST);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidNicknameCommands")
		@DisplayName("빈 닉네임은 캐릭터 변경 여부와 무관하게 거절한다")
		void invalidNicknameIsRejectedRegardlessOfCharacterChange(
				String scenario, String newNickname, Long characterId) {
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", newNickname, characterId);

			assertThatThrownBy(() -> service.updateProfile(command))
					.isInstanceOf(InvalidNicknameException.class);

			then(loadUserForProfilePort).shouldHaveNoInteractions();
			then(recordUserAccountPort).shouldHaveNoInteractions();
			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(nicknameHoldPort).shouldHaveNoInteractions();
			then(fixedCharacterAvatarPort).shouldHaveNoInteractions();
		}

		private static Stream<Arguments> invalidNicknameCommands() {
			return Stream.of(
					Arguments.of("빈 닉네임과 캐릭터 변경 없음", "", null),
					Arguments.of("빈 닉네임과 캐릭터 변경 있음", "", 2L),
					Arguments.of("Unicode 공백 닉네임과 캐릭터 변경 없음", " \u2003\u3000 ", null),
					Arguments.of("Unicode 공백 닉네임과 캐릭터 변경 있음", " \u2003\u3000 ", 2L));
		}

		@Test
		@DisplayName("정규화 후 현재 닉네임이면 점유 없이 변경 없음으로 처리한다")
		void normalizedCurrentNicknameDoesNotRequireHold() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", " \u2003현재닉\u3000 ", null);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));

			UpdateProfileResult result = service.updateProfile(command);

			assertThat(result.success()).isTrue();
			assertThat(result.newNickname()).isEqualTo("현재닉");
			then(checkUserUniquenessPort).shouldHaveNoInteractions();
			then(nicknameHoldPort).shouldHaveNoInteractions();
			then(recordUserAccountPort).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("공백 형태가 다른 점유로 정규화된 닉네임을 변경한다")
		void updatesNicknameUsingNormalizedHold() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", " \u2003새닉\u3000 ", null);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(nicknameHoldPort.isHeldBy(eq(new Nickname("새닉")), eq("user-1"), any(Instant.class))).willReturn(true);
			given(recordUserAccountPort.recordUserAccount(user)).willReturn(user);

			UpdateProfileResult result = service.updateProfile(command);

			assertThat(result.success()).isTrue();
			assertThat(result.newNickname()).isEqualTo("새닉");
			assertThat(user.getNickname()).isEqualTo("새닉");
			then(nicknameHoldPort).should().release(new Nickname("새닉"), "user-1");
		}

		@Test
		@DisplayName("실제 점유 어댑터가 공백 형태가 다른 예약과 변경을 같은 닉네임으로 처리한다")
		void realHoldAdapterConnectsWhitespaceVariantReservationAndUpdate() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			InMemoryNicknameHoldAdapter holdAdapter = new InMemoryNicknameHoldAdapter(new NicknamePolicy());
			UserProfileCommandService integratedService = new UserProfileCommandService(
					loadUserForProfilePort,
					recordUserAccountPort,
					checkUserUniquenessPort,
					fixedCharacterAvatarPort,
					holdAdapter);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));

			boolean reserved = integratedService.reserveIfAvailable(" \u2003새닉\u3000 ", "user-1");
			UpdateProfileResult result = integratedService.updateProfile(
					new UpdateProfileCommand("user-1", "\t새닉\n", null));

			assertThat(reserved).isTrue();
			assertThat(result.success()).isTrue();
			assertThat(result.newNickname()).isEqualTo("새닉");
			assertThat(holdAdapter.isHeldBy(new Nickname("새닉"), "user-1", Instant.now())).isFalse();
		}

		@Test
		@DisplayName("존재하지 않는 사용자면 예외 발생")
		void nonExistentUserThrows() {
			UpdateProfileCommand command = new UpdateProfileCommand("unknown", "새닉", null);
			given(loadUserForProfilePort.loadProfileUser("unknown")).willReturn(Optional.empty());

				assertThatThrownBy(() -> service.updateProfile(command))
						.isInstanceOfSatisfying(UserNotFoundException.class,
								ex -> assertThat(ex.getErrorCode()).isEqualTo(UserErrorCode.USER_NOT_FOUND));
		}

		@Test
		@DisplayName("존재하지 않는 캐릭터면 RESOURCE_NOT_FOUND 실패 응답")
		void nonExistentCharacterReturnsResourceNotFoundFailure() {
			User user = new User("user-1", "test@test.com", "pw", "닉네임", 1L);
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", null, 999L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(fixedCharacterAvatarPort.resolveFixedCharacterObjectKey(999L)).willReturn(Optional.empty());

			UpdateProfileResult result = service.updateProfile(command);

			assertThat(result.success()).isFalse();
			assertThat(result.failureReason()).isEqualTo(UpdateProfileFailureReason.RESOURCE_NOT_FOUND);
			assertThat(result.message()).isEqualTo("존재하지 않는 캐릭터입니다.");
		}

		@Test
		@DisplayName("캐릭터 변경 시 식별자를 저장하고 canonical object key는 응답한다")
		void storesCharacterIdentifierWhenCharacterChanges() {
			User user = new User("user-1", "test@test.com", "pw", "닉네임", 1L);
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", null, 2L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(fixedCharacterAvatarPort.resolveFixedCharacterObjectKey(2L))
					.willReturn(Optional.of("public/characters/fixed/base_image_2.webp"));
			given(recordUserAccountPort.recordUserAccount(any(User.class))).willReturn(user);

			UpdateProfileResult result = service.updateProfile(command);

			assertThat(result.success()).isTrue();
			assertThat(result.avatarReference()).isEqualTo("public/characters/fixed/base_image_2.webp");
			verify(recordUserAccountPort).recordUserAccount(same(user));
			verify(recordUserAccountPort).recordUserAccount(argThat(savedUser -> savedUser.getCharacterId() == 2L));
		}

		@Test
		@DisplayName("닉네임 변경 성공 후 점유를 해제한다")
		void releasesNicknameHoldAfterSuccessfulUpdate() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", "새닉", null);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(nicknameHoldPort.isHeldBy(eq(new Nickname("새닉")), eq("user-1"), any(Instant.class))).willReturn(true);
			given(recordUserAccountPort.recordUserAccount(user)).willReturn(user);

			UpdateProfileResult result = service.updateProfile(command);

			assertThat(result.success()).isTrue();
			assertThat(result.avatarReference()).isNull();
			verify(nicknameHoldPort).release(new Nickname("새닉"), "user-1");
		}

		@Test
		@DisplayName("닉네임 저장 충돌 시 예외를 전파하고 점유를 유지한다")
		void keepsNicknameHoldAfterPersistenceConflict() {
			User user = new User("user-1", "test@test.com", "pw", "현재닉", 1L);
			UpdateProfileCommand command = new UpdateProfileCommand("user-1", "새닉", null);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(nicknameHoldPort.isHeldBy(eq(new Nickname("새닉")), eq("user-1"), any(Instant.class))).willReturn(true);
			given(recordUserAccountPort.recordUserAccount(user)).willThrow(new NicknameAlreadyExistsException("새닉"));

			assertThatThrownBy(() -> service.updateProfile(command))
					.isInstanceOf(NicknameAlreadyExistsException.class)
					.hasMessageContaining("새닉");
			verify(nicknameHoldPort, never()).release(any(Nickname.class), anyString());
		}
	}

	@Nested
	@DisplayName("updateProfileImage - 프로필 이미지 변경")
	class UpdateProfileImage {

		@Test
		@DisplayName("유효한 캐릭터 ID로 프로필 이미지를 변경한다")
		void validCharacterIdUpdatesImage() {
			User user = new User("user-1", "test@test.com", "pw", "닉네임", 1L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(fixedCharacterAvatarPort.resolveFixedCharacterObjectKey(1L))
					.willReturn(Optional.of("public/characters/fixed/base_image_1.webp"));
			given(recordUserAccountPort.recordUserAccount(any(User.class))).willReturn(user);

			service.updateProfileImage("user-1", 1L);

			verify(recordUserAccountPort).recordUserAccount(argThat(savedUser -> savedUser.getCharacterId() == 1L));
		}

		@Test
		@DisplayName("존재하지 않는 캐릭터 이미지면 ProfileImageNotFoundException 발생")
		void nonExistentCharacterThrowsNotFound() {
			User user = new User("user-1", "test@test.com", "pw", "닉네임", 1L);
			given(loadUserForProfilePort.loadProfileUser("user-1")).willReturn(Optional.of(user));
			given(fixedCharacterAvatarPort.resolveFixedCharacterObjectKey(999L)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.updateProfileImage("user-1", 999L))
					.isInstanceOf(ProfileImageNotFoundException.class);
			verify(recordUserAccountPort, never()).recordUserAccount(any());
		}
	}
}
