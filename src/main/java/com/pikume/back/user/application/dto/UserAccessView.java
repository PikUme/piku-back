package com.pikume.back.user.application.dto;

public record UserAccessView(
		String id,
		boolean withdrawn,
		UserAccessProfileStatus profileSetupStatus
) {
}
