package com.pikume.back.user.auth.adapter.out.email;

import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;
import com.pikume.back.global.email.OutboundEmailException;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@DisplayName("VerificationEmailAdapter")
class VerificationEmailAdapterTest {

	@Test
	@DisplayName("Spring Mail 발송 실패를 Application 오류 의미로 번역한다")
	void translatesMailSendFailure() {
		SendOutboundEmailPort emailPort = mock(SendOutboundEmailPort.class);
		willThrow(new OutboundEmailException("resend", "provider_unavailable", 503))
				.given(emailPort).send(org.mockito.ArgumentMatchers.any());
		VerificationEmailAdapter adapter = adapter(emailPort);

		assertThatThrownBy(() -> adapter.issueVerificationEmail("user@example.com"))
				.isInstanceOfSatisfying(AuthException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_SEND_FAILURE));
	}

	@Test
	@DisplayName("기존 제목, 발신자 표시 이름과 인증 본문을 공통 메일 포트로 전달한다")
	void createsVerificationEmail() {
		SendOutboundEmailPort emailPort = mock(SendOutboundEmailPort.class);
		VerificationEmailAdapter adapter = adapter(emailPort);

		adapter.deliverVerificationCode("user@example.com", "123456");

		org.mockito.ArgumentCaptor<OutboundEmailMessage> captor =
				org.mockito.ArgumentCaptor.forClass(OutboundEmailMessage.class);
		then(emailPort).should().send(captor.capture());
		OutboundEmailMessage message = captor.getValue();
		assertThat(message.from().address()).isEqualTo("no-reply@example.com");
		assertThat(message.from().displayName()).isEqualTo("PikUme | 피쿠");
		assertThat(message.to().address()).isEqualTo("user@example.com");
		assertThat(message.subject()).isEqualTo("[PikUme] 이메일 인증");
		assertThat(message.html()).contains("123456");
	}

	private VerificationEmailAdapter adapter(SendOutboundEmailPort emailPort) {
		VerificationEmailAdapter adapter = new VerificationEmailAdapter(emailPort);
		ReflectionTestUtils.setField(adapter, "senderAddress", "no-reply@example.com");
		return adapter;
	}
}
