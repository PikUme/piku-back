package com.pikume.back.security.adapter.in.web;

import com.pikume.back.global.port.out.ResolveObjectUrlPort;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.auth.application.dto.LoginResult;
import com.pikume.back.security.adapter.in.web.dto.response.UserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthUserResponseMapper {

	private final ResolveObjectUrlPort resolveObjectUrlPort;

	public UserInfo toDisplayUserInfo(LoginResult.UserInfo userInfo) {
		if (userInfo == null) {
			return null;
		}
		return new UserInfo(
				userInfo.id(),
				userInfo.nickname(),
				resolveAvatarUrl(userInfo.avatarReference()), userInfo.profileSetupStatus(), userInfo.characterId());
	}

	public UserInfo toDisplayUserInfo(UserPrincipal userDetails) {
		if (userDetails == null) {
			return null;
		}
		return new UserInfo(
				userDetails.getId(),
				userDetails.getNickname(),
				resolveAvatarUrl(userDetails.getAvatarReference()), userDetails.getProfileSetupStatus(), userDetails.getCharacterId());
	}

	private String resolveAvatarUrl(UserAvatarReference reference) {
		if (reference == null) {
			return null;
		}
		if (reference.absoluteUrl()) {
			return reference.value();
		}
		return resolveObjectUrlPort.resolveObjectUrl(reference.value(), reference.publiclyAccessible());
	}
}
