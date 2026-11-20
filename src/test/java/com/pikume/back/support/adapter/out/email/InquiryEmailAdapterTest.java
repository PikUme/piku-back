package com.pikume.back.support.adapter.out.email;

import com.pikume.back.global.email.OutboundEmailException;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import com.pikume.back.support.application.dto.InquiryAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

@DisplayName("InquiryEmailAdapter")
class InquiryEmailAdapterTest {

	@Test
	@DisplayName("Support 소유 제목과 본문으로 첨부 포함 운영 메일을 전송한다")
	void sendsSupportOwnedInquiryEmail() {
		SendOutboundEmailPort emailPort = mock(SendOutboundEmailPort.class);
		InquiryEmailAdapter adapter = adapter(emailPort);

		adapter.sendInquiryNotification(
				"문의 내용",
				new InquiryAttachment("inquiry.png", "image/png", "data".getBytes()));

		org.mockito.ArgumentCaptor<OutboundEmailMessage> captor =
				org.mockito.ArgumentCaptor.forClass(OutboundEmailMessage.class);
		then(emailPort).should().send(captor.capture());
		OutboundEmailMessage message = captor.getValue();
		assertThat(message.subject()).isEqualTo("[PikUme] 피드백");
		assertThat(message.to().address()).isEqualTo("admin@example.com");
		assertThat(message.from().address()).isEqualTo("no-reply@example.com");
		assertThat(message.attachments()).singleElement().satisfies(attachment -> {
			assertThat(attachment.filename()).isEqualTo("inquiry.png");
			assertThat(attachment.contentType()).isEqualTo("image/png");
			assertThat(attachment.content()).isEqualTo("data".getBytes());
		});
	}

	@Test
	@DisplayName("메일 Provider 실패는 호출자가 현재 실패 무시 정책을 적용할 수 있게 전파한다")
	void propagatesMailProviderFailure() {
		SendOutboundEmailPort emailPort = mock(SendOutboundEmailPort.class);
		OutboundEmailException failure = new OutboundEmailException("resend", "provider_rejected", 429);
		willThrow(failure).given(emailPort).send(any());

		assertThatThrownBy(() -> adapter(emailPort)
				.sendInquiryNotification("문의 내용", null))
				.isSameAs(failure);
	}

	private InquiryEmailAdapter adapter(SendOutboundEmailPort emailPort) {
		InquiryEmailAdapter adapter =
				new InquiryEmailAdapter(emailPort, new InquiryEmailTemplate());
		ReflectionTestUtils.setField(adapter, "senderAddress", "no-reply@example.com");
		ReflectionTestUtils.setField(adapter, "feedbackRecipient", "admin@example.com");
		return adapter;
	}
}
