package com.pikume.back.security.adapter.in.web.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserInfo {
	private String id;
	private String nickname;
	private String avatarUrl;
	private String profileSetupStatus;
	private Long characterId;

	public UserInfo(String id, String nickname, String avatarUrl, String profileSetupStatus) {
		this(id, nickname, avatarUrl, profileSetupStatus, null);
	}

	public UserInfo(String id, String nickname, String avatarUrl) {
		this(id, nickname, avatarUrl, "COMPLETED");
	}
}
