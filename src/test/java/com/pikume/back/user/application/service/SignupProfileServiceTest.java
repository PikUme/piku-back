package com.pikume.back.user.application.service;

import com.pikume.back.user.domain.vo.Nickname;
import com.pikume.back.user.application.exception.SignupProfileException;
import com.pikume.back.user.application.exception.SignupProfileFailure;
import com.pikume.back.user.application.port.out.*;
import com.pikume.back.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignupProfileServiceTest {
	@InjectMocks UserProfileCommandService service;
	@Mock LoadUserForProfilePort users;
	@Mock RecordUserAccountPort accounts;
	@Mock CheckUserUniquenessPort uniqueness;
	@Mock ResolveFixedCharacterAvatarPort characters;
	@Mock NicknameHoldPort holds;

	@Test void reserveReturnsNormalizedNicknameWithOriginalExpiry() {
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(pending()));
		Instant expiry = Instant.now().plusSeconds(60);
		given(holds.tryAcquire(eq(new Nickname(" nick ")), eq("user-1"), any())).willReturn(true);
		given(holds.heldUntil(eq(new Nickname(" nick ")), eq("user-1"), any())).willReturn(Optional.of(expiry));

		var result = service.reserveSignupNickname("user-1", " nick ");

		assertThat(result.nickname()).isEqualTo("nick");
		assertThat(result.expiresAt()).isEqualTo(expiry);
		var order = inOrder(holds, users);
		order.verify(holds).lockNicknameWrites();
		order.verify(users).loadProfileUserForUpdate("user-1");
	}

	@Test void reserveRejectsReservedPrefix() {
		assertFailure(() -> service.reserveSignupNickname("user-1", "  가입대기_123　 "), SignupProfileFailure.INVALID_NICKNAME);
		verify(holds, never()).tryAcquire(any(), any(), any());
	}

	@Test void completeRequiresActiveOwnHoldAndPreservesStateOnFailure() {
		User user = pending();
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));

		assertFailure(() -> service.completeSignupProfile("user-1", "nick", 1L), SignupProfileFailure.HOLD_REQUIRED);

		assertThat(user.isProfileSetupRequired()).isTrue();
		verify(holds, never()).release(any(), any());
		verify(accounts, never()).recordUserAccount(any());
	}

	@Test void invalidCharacterPreservesHoldAndPendingState() {
		User user = pending();
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));
		given(holds.isHeldBy(eq(new Nickname("nick")), eq("user-1"), any())).willReturn(true);

		assertFailure(() -> service.completeSignupProfile("user-1", "nick", 9L), SignupProfileFailure.INVALID_CHARACTER);

		assertThat(user.isProfileSetupRequired()).isTrue();
		verify(holds, never()).release(any(), any());
	}

	@Test void completionAllowsDefaultCharacterAndReleasesAfterSaving() {
		User user = pending();
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));
		given(holds.isHeldBy(eq(new Nickname("nick")), eq("user-1"), any())).willReturn(true);
		given(characters.resolveFixedCharacterObjectKey(1L)).willReturn(Optional.of("default.webp"));

		var result = service.completeSignupProfile("user-1", "nick", 1L);

		assertThat(result.profileSetupStatus()).isEqualTo("COMPLETED");
		assertThat(user.getNickname()).isEqualTo("nick");
		var order = inOrder(holds, users, accounts);
		order.verify(holds).lockNicknameWrites();
		order.verify(users).loadProfileUserForUpdate("user-1");
		order.verify(holds).isHeldBy(eq(new Nickname("nick")), eq("user-1"), any());
		order.verify(accounts).recordUserAccount(user);
		order.verify(holds).release(new Nickname("nick"), "user-1");
	}

	@Test void identicalCompletedRetryNeedsNoHoldOrCharacterLookup() {
		User user = new User("user-1", "user@example.com", null, "nick", 1L);
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));

		assertThat(service.completeSignupProfile("user-1", "nick", 1L).profileSetupStatus()).isEqualTo("COMPLETED");

		verify(holds, never()).isHeldBy(any(), any(), any());
		verifyNoInteractions(characters, accounts);
		assertFailure(() -> service.completeSignupProfile("user-1", "other", 1L), SignupProfileFailure.PROFILE_ALREADY_COMPLETED);
	}

	@Test void identicalLegacyCompletedRetryAcceptsHistoricalReservedPrefix() {
		User user = new User("user-1", "user@example.com", null, "가입대기_old", 1L);
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));

		assertThat(service.completeSignupProfile("user-1", "가입대기_old", 1L).nickname()).isEqualTo("가입대기_old");

		verifyNoInteractions(characters, accounts);
		verify(holds, never()).isHeldBy(any(), any(), any());
	}

	@Test void withdrawnUserCannotCompleteEvenIdenticalRetry() {
		User user = new User("user-1", "user@example.com", null, "nick", 1L);
		user.withdraw();
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));
		assertFailure(() -> service.completeSignupProfile("user-1", "nick", 1L), SignupProfileFailure.USER_UNAVAILABLE);
	}

	@Test void legacyReservationRejectsPendingUser() {
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(pending()));
		assertThat(service.reserveIfAvailable("nick", "user-1")).isFalse();
		verify(holds, never()).tryAcquire(any(), any(), any());
	}

	@Test void withdrawPendingUserReleasesAllHoldsAfterSaving() {
		User user = pending();
		given(users.loadProfileUserForUpdate("user-1")).willReturn(Optional.of(user));

		service.withdrawPendingSignup("user-1");

		assertThat(user.isWithdrawn()).isTrue();
		var order = inOrder(holds, users, accounts);
		order.verify(holds).lockNicknameWrites();
		order.verify(users).loadProfileUserForUpdate("user-1");
		order.verify(accounts).recordUserAccount(user);
		order.verify(holds).releaseForUser("user-1");
		service.withdrawPendingSignup("user-1");
		verify(accounts, times(1)).recordUserAccount(user);
	}

	@Test void completedUserCannotWithdrawThroughSignupEndpoint() {
		given(users.loadProfileUserForUpdate("user-1"))
			.willReturn(Optional.of(new User("user-1", "user@example.com", null, "nick", 1L)));
		assertFailure(() -> service.withdrawPendingSignup("user-1"), SignupProfileFailure.PROFILE_ALREADY_COMPLETED);
		verifyNoInteractions(accounts);
	}

	private User pending() {
		User user = User.pending("user@example.com", null, "가입대기_123", 1L);
		org.springframework.test.util.ReflectionTestUtils.setField(user, "id", "user-1");
		return user;
	}
	private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, SignupProfileFailure reason) {
		assertThatThrownBy(action).isInstanceOfSatisfying(SignupProfileException.class,
			exception -> assertThat(exception.getFailure()).isEqualTo(reason));
	}
}
