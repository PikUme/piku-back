package com.pikume.back.user.application.dto;

public record UserIdentityView(
		String id,
		String passwordHash,
		String nickname,
		UserAvatarReference avatarReference,
		String profileSetupStatus,
		Long characterId
) {
	public UserIdentityView(String id, String passwordHash, String nickname, UserAvatarReference avatarReference, String profileSetupStatus) {
		this(id, passwordHash, nickname, avatarReference, profileSetupStatus, null);
	}
	public UserIdentityView(String id, String passwordHash, String nickname, UserAvatarReference avatarReference) {
		this(id, passwordHash, nickname, avatarReference, "COMPLETED");
	}
}
