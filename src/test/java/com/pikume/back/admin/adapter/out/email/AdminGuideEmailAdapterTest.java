package com.pikume.back.admin.adapter.out.email;

import com.pikume.back.global.email.OutboundEmailException;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

@DisplayName("AdminGuideEmailAdapter")
class AdminGuideEmailAdapterTest {

	@Test
	@DisplayName("기존 관리자 안내 메일 내용과 수신자 정보를 공통 메일 포트로 전달한다")
	void sendsAdminGuideMessage() {
		SendOutboundEmailPort emailPort = mock(SendOutboundEmailPort.class);
		AdminGuideEmailAdapter adapter = adapter(emailPort);

		adapter.sendAccountCreatedGuide(
				"admin@example.com",
				"temporary-admin",
				LocalDateTime.parse("2026-11-14T12:30:00"));

		org.mockito.ArgumentCaptor<OutboundEmailMessage> captor =
				org.mockito.ArgumentCaptor.forClass(OutboundEmailMessage.class);
		then(emailPort).should().send(captor.capture());
		OutboundEmailMessage message = captor.getValue();
		assertThat(message.from().address()).isEqualTo("no-reply@example.com");
		assertThat(message.from().displayName()).isEqualTo("Pikume Ops");
		assertThat(message.to().address()).isEqualTo("admin@example.com");
		assertThat(message.subject()).isEqualTo("[Pikume Ops] 관리자 계정 생성 안내");
		assertThat(message.html()).contains("https://ops.example.com", "temporary-admin", "2026-11-14T12:30");
		assertThat(message.attachments()).isEmpty();
	}

	@Test
	@DisplayName("메일 전송 실패를 호출자에게 전달해 안내 메일 실패 상태를 유지한다")
	void propagatesProviderFailure() {
		SendOutboundEmailPort emailPort = mock(SendOutboundEmailPort.class);
		OutboundEmailException failure = new OutboundEmailException("resend", "provider_rejected", 422);
		willThrow(failure).given(emailPort).send(any());

		assertThatThrownBy(() -> adapter(emailPort)
				.sendAccountCreatedGuide("admin@example.com", "temporary-admin", LocalDateTime.now()))
				.isSameAs(failure);
	}

	private AdminGuideEmailAdapter adapter(SendOutboundEmailPort emailPort) {
		AdminGuideEmailAdapter adapter = new AdminGuideEmailAdapter(emailPort);
		ReflectionTestUtils.setField(adapter, "senderAddress", "no-reply@example.com");
		ReflectionTestUtils.setField(adapter, "adminOpsUrl", "https://ops.example.com");
		return adapter;
	}
}
