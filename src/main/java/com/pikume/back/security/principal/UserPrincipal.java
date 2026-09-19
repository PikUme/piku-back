package com.pikume.back.security.principal;

import com.pikume.back.user.application.dto.UserAvatarReference;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class UserPrincipal implements UserDetails {

	private final String id;
	private final String nickname;
	private final UserAvatarReference avatarReference;
	private String profileSetupStatus = "COMPLETED";
	private Long characterId;

	public static UserPrincipal withProfileState(String id, String nickname, UserAvatarReference avatarReference, String status) {
		return withProfileState(id, nickname, avatarReference, status, null);
	}

	public static UserPrincipal withProfileState(String id, String nickname, UserAvatarReference avatarReference, String status, Long characterId) {
		UserPrincipal principal = new UserPrincipal(id, nickname, avatarReference);
		principal.profileSetupStatus = status;
		principal.characterId = characterId;
		return principal;
	}

	public UserPrincipal(String id, String nickname) {
		this(id, nickname, null);
	}

	public static UserPrincipal withAvatarReference(
			String id,
			String nickname,
			UserAvatarReference avatarReference) {
		return new UserPrincipal(id, nickname, avatarReference);
	}

	private UserPrincipal(String id, String nickname, UserAvatarReference avatarReference) {
		this.id = id;
		this.nickname = nickname;
		this.avatarReference = avatarReference;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@Override
	public String getPassword() {
		return null;
	}

	@Override
	public String getUsername() {
		return id;
	}
}
