package com.pikume.back.support.adapter.out.email;

import com.pikume.back.support.application.dto.InquiryAttachment;
import com.pikume.back.support.application.port.out.SendInquiryNotificationPort;
import com.pikume.back.global.email.EmailAddress;
import com.pikume.back.global.email.EmailAttachment;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class InquiryEmailAdapter implements SendInquiryNotificationPort {

	private final SendOutboundEmailPort emailPort;
	private final InquiryEmailTemplate emailTemplate;

	@Value("${outbound-email.from-address}")
	private String senderAddress;

	@Value("${outbound-email.feedback-recipient}")
	private String feedbackRecipient;

	@Override
	public void sendInquiryNotification(String content, InquiryAttachment attachment) {
		List<EmailAttachment> attachments = attachment == null || attachment.isEmpty()
				? List.of()
				: List.of(new EmailAttachment(
						attachment.originalFilename(), attachment.contentType(), attachment.bytes()));

		emailPort.send(new OutboundEmailMessage(
				new EmailAddress(senderAddress, "PikUme | 피쿠"),
				new EmailAddress(feedbackRecipient, ""),
				emailTemplate.subject(),
				emailTemplate.render(content),
				attachments));
	}
}
