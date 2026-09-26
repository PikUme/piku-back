package com.pikume.back.user.auth.application.dto;

import com.pikume.back.user.application.dto.UserAvatarReference;

public record LoginResult(String accessToken, String refreshToken, UserInfo userInfo) {
	public record UserInfo(String id, String nickname, UserAvatarReference avatarReference, String profileSetupStatus, Long characterId) {
		public UserInfo(String id, String nickname, UserAvatarReference avatarReference, String profileSetupStatus) {
			this(id, nickname, avatarReference, profileSetupStatus, null);
		}
		public UserInfo(String id, String nickname, UserAvatarReference avatarReference) {
			this(id, nickname, avatarReference, "COMPLETED");
		}
	}
}
