package com.pikume.back.support.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.pikume.back.support.application.dto.InquiryAttachment;
import com.pikume.back.support.application.dto.SubmitInquiryCommand;
import com.pikume.back.support.application.exception.SupportErrorCode;
import com.pikume.back.support.application.exception.SupportException;
import com.pikume.back.support.application.port.in.SubmitInquiryUseCase;
import com.pikume.back.support.application.port.out.RecordInquiryPort;
import com.pikume.back.support.application.port.out.SendInquiryNotificationPort;
import com.pikume.back.support.application.port.out.StoreInquiryAttachmentPort;
import com.pikume.back.support.application.port.out.VerifyInquirySubmitterPort;
import com.pikume.back.support.domain.Inquiry;

@Service
@RequiredArgsConstructor
@Slf4j
public class InquiryService implements SubmitInquiryUseCase {

	private final VerifyInquirySubmitterPort verifyInquirySubmitterPort;
	private final StoreInquiryAttachmentPort storeInquiryAttachmentPort;
	private final SendInquiryNotificationPort sendInquiryNotificationPort;
	private final RecordInquiryPort recordInquiryPort;

	@Override
	public void submitInquiry(SubmitInquiryCommand command) {
		if (!verifyInquirySubmitterPort.inquirySubmitterExists(command.userId())) {
			throw new SupportException(SupportErrorCode.SUBMITTER_NOT_FOUND);
		}

		String attachmentReference = null;
		InquiryAttachment attachment = command.attachment();
		if (attachment != null && !attachment.isEmpty()) {
			attachmentReference = storeAttachment(attachment, command.userId());
		}

		try {
			sendInquiryNotificationPort.sendInquiryNotification(command.content(), attachment);
		} catch (RuntimeException exception) {
			log.warn("event=inquiry_notification_failed outcome=ignored reason={}",
					exception.getClass().getSimpleName());
		}

		Inquiry inquiry;
		try {
			inquiry = Inquiry.submit(command.userId(), command.content(), attachmentReference);
		} catch (IllegalArgumentException exception) {
			throw new SupportException(SupportErrorCode.INVALID_INQUIRY, exception);
		}
		recordInquiryPort.recordInquiry(inquiry);
		log.info("event=inquiry_recorded outcome=success userId={}", command.userId());
	}

	private String storeAttachment(InquiryAttachment attachment, String userId) {
		try {
			return storeInquiryAttachmentPort.storeInquiryAttachment(attachment, userId);
		} catch (SupportException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new SupportException(SupportErrorCode.ATTACHMENT_STORAGE_FAILED, exception);
		}
	}
}
