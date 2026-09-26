package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.AvatarCharacterSelection;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.dto.UserReferenceView;
import com.pikume.back.user.application.port.in.QueryUserReferenceUseCase;
import com.pikume.back.user.application.port.out.LoadUserReferencePort;
import com.pikume.back.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserReferenceQueryService implements QueryUserReferenceUseCase {

	private final LoadUserReferencePort loadUserReferencePort;
	private final UserAvatarReferenceResolver userAvatarReferenceResolver;

	@Override
	public Optional<UserReferenceView> queryUserReference(String userId) {
		return loadUserReferencePort.loadReference(userId)
				.filter(user -> !user.isProfileSetupRequired())
				.map(this::toReferenceView);
	}

	private UserReferenceView toReferenceView(User user) {
		AvatarCharacterSelection selection = new AvatarCharacterSelection(user.getId(), user.getCharacterId());
		UserAvatarReference avatarReference = userAvatarReferenceResolver.resolveRequired(List.of(selection))
				.get(selection);
		return new UserReferenceView(user.getId(), user.getNickname(), avatarReference);
	}
}
