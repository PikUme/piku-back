package com.pikume.back.user.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pikume.back.user.application.dto.ProfilePreviewResult;
import com.pikume.back.user.application.dto.AvatarCharacterSelection;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.dto.UserProfileResult;
import com.pikume.back.user.application.exception.UserNotFoundException;
import com.pikume.back.user.application.port.in.QueryUserProfileUseCase;
import com.pikume.back.user.application.port.out.LoadUserForProfilePort;
import com.pikume.back.user.application.port.out.QueryProfileDiaryMetricsPort;
import com.pikume.back.user.application.port.out.QueryProfileSocialMetricsPort;
import com.pikume.back.user.domain.User;

import java.util.List;

/**
 * 프로필 조회 Application Service
 * QueryUserProfileUseCase를 구현하여 프로필 조회 유스케이스를 처리합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserProfileQueryService implements QueryUserProfileUseCase {

	private final LoadUserForProfilePort loadUserForProfilePort;
	private final QueryProfileSocialMetricsPort socialMetricsPort;
	private final QueryProfileDiaryMetricsPort diaryMetricsPort;
	private final UserAvatarReferenceResolver userAvatarReferenceResolver;

	@Override
	public ProfilePreviewResult queryProfilePreview(String profileId, String currentUserId) {
		User profile = loadUserForProfilePort.loadProfileUser(profileId)
					.filter(user -> !user.isProfileSetupRequired())
					.orElseThrow(UserNotFoundException::new);

		int friendCount = socialMetricsPort.queryFriendCount(profileId);
		long diaryCount = diaryMetricsPort.queryVisibleDiaryCount(profileId, currentUserId);
		String friendshipStatus = socialMetricsPort.queryFriendshipStatus(currentUserId, profileId);
		AvatarCharacterSelection selection = new AvatarCharacterSelection(profile.getId(), profile.getCharacterId());
		UserAvatarReference avatarReference = userAvatarReferenceResolver.resolveRequired(List.of(selection))
				.get(selection);

		log.info("event=profile_preview_loaded outcome=success userId={} friendCount={} diaryCount={} friendStatus={}",
				profileId, friendCount, diaryCount, friendshipStatus);

		return new ProfilePreviewResult(profileId, profile.getNickname(), avatarReference, friendCount, diaryCount,
				friendshipStatus);
	}

	@Override
	public UserProfileResult queryUserProfile(String profileId, String currentUserId) {
		ProfilePreviewResult preview = queryProfilePreview(profileId, currentUserId);
		boolean isOwner = profileId.equals(currentUserId);
		List<UserProfileResult.MonthlyDiaryCount> monthlyDiaryCount = diaryMetricsPort
				.queryVisibleMonthlyDiaryCounts(profileId, currentUserId)
				.stream()
				.map(count -> new UserProfileResult.MonthlyDiaryCount(count.year(), count.month(), count.count()))
				.toList();

		return new UserProfileResult(
				preview.id(),
				preview.nickname(),
				preview.avatarReference(),
				preview.friendCount(),
				preview.diaryCount(),
				preview.friendStatus(),
				isOwner,
				monthlyDiaryCount);
	}
}
