package com.pikume.back.global.email;

import java.util.List;
import java.util.Objects;

public record OutboundEmailMessage(
		EmailAddress from,
		EmailAddress to,
		String subject,
		String html,
		List<EmailAttachment> attachments) {

	public OutboundEmailMessage {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(html, "html");
		attachments = List.copyOf(attachments);
	}
}
