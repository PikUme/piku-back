package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.AvatarCharacterSelection;
import com.pikume.back.user.application.dto.UserIdentityView;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import com.pikume.back.user.application.port.out.LoadUserForAuthenticationPort;
import com.pikume.back.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserIdentityQueryService implements QueryUserIdentityUseCase {

	private final LoadUserForAuthenticationPort loadUserForAuthenticationPort;
	private final UserAvatarReferenceResolver userAvatarReferenceResolver;

	@Override
	public Optional<UserIdentityView> queryUserIdentityByEmail(String email) {
		return loadUserForAuthenticationPort.loadForLogin(email)
				.filter(user -> !user.isWithdrawn())
				.map(this::toIdentityView);
	}

	@Override
	public Optional<UserIdentityView> queryUserIdentityById(String userId) {
		return loadUserForAuthenticationPort.loadForSession(userId)
				.filter(user -> !user.isWithdrawn())
				.map(this::toIdentityView);
	}

	private UserIdentityView toIdentityView(User user) {
		AvatarCharacterSelection selection = new AvatarCharacterSelection(user.getId(), user.getCharacterId());
		UserAvatarReference avatarReference = userAvatarReferenceResolver.resolveRequired(List.of(selection))
				.get(selection);
		return new UserIdentityView(
				user.getId(),
				user.getPassword(),
				user.getNickname(),
				avatarReference, user.getProfileSetupStatus().name(), user.getCharacterId());
	}
}
