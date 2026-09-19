package com.pikume.back.user.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pikume.back.user.application.dto.AvatarCharacterSelection;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.dto.UserSummaryView;
import com.pikume.back.user.application.port.in.QueryUserSummaryUseCase;
import com.pikume.back.user.application.port.out.LoadUserReferencePort;
import com.pikume.back.user.domain.User;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSummaryQueryService implements QueryUserSummaryUseCase {

	private final LoadUserReferencePort loadUserReferencePort;
	private final UserAvatarReferenceResolver userAvatarReferenceResolver;

	@Override
	public Map<String, UserSummaryView> queryUserSummaries(Set<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Map.of();
		}

		List<User> users = loadUserReferencePort.loadReferences(userIds).stream()
				.filter(user -> !user.isProfileSetupRequired())
				.toList();
		if (users.isEmpty()) {
			return Map.of();
		}
		Map<AvatarCharacterSelection, UserAvatarReference> avatarReferences = userAvatarReferenceResolver
				.resolveRequired(users.stream()
						.map(user -> new AvatarCharacterSelection(user.getId(), user.getCharacterId()))
						.toList());

		return users.stream()
				.collect(Collectors.toMap(
						User::getId,
						user -> new UserSummaryView(
								user.getId(),
								user.getNickname(),
								avatarReferences.get(new AvatarCharacterSelection(
										user.getId(), user.getCharacterId()))),
						(left, right) -> left));
	}
}
