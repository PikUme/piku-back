package com.pikume.back.global.email;

import java.util.Objects;

public record EmailAddress(String address, String displayName) {

	public EmailAddress {
		Objects.requireNonNull(address, "address");
		displayName = displayName == null ? "" : displayName;
	}
}
