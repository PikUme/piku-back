package com.pikume.back.user.application.dto;

import com.pikume.back.user.domain.ProfileSetupStatus;

public enum UserAccessProfileStatus {
	REQUIRED,
	COMPLETED;

	public static UserAccessProfileStatus from(ProfileSetupStatus status) {
		return valueOf(status.name());
	}
}
