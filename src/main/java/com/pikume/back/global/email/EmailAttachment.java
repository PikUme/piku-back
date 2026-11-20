package com.pikume.back.global.email;

import java.util.Objects;

public record EmailAttachment(String filename, String contentType, byte[] content) {

	public EmailAttachment {
		Objects.requireNonNull(filename, "filename");
		Objects.requireNonNull(content, "content");
		content = content.clone();
	}

	@Override
	public byte[] content() {
		return content.clone();
	}
}
