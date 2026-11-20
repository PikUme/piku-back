package com.pikume.back.admin.adapter.out.email;

import com.pikume.back.admin.application.port.out.SendAdminGuideEmailPort;
import com.pikume.back.global.email.EmailAddress;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AdminGuideEmailAdapter implements SendAdminGuideEmailPort {

	private final SendOutboundEmailPort emailPort;

	@Value("${outbound-email.from-address}")
	private String senderAddress;

	@Value("${admin.ops-url:https://pikume-ops.pikume.com}")
	private String adminOpsUrl;

	@Override
	public void sendAccountCreatedGuide(String email, String temporaryLoginId, LocalDateTime temporaryCredentialExpiresAt) {
		String html = """
					<p>Pikume 관리자 계정이 생성되었습니다.</p>
					<p>관리자 페이지: %s</p>
					<p>임시 로그인 아이디: %s</p>
					<p>임시 자격 증명 만료 시각: %s</p>
					<p>임시 패스워드는 계정 발급자에게 별도 채널로 전달받아 주세요.</p>
					<p>이 이메일에는 임시 패스워드가 포함되지 않습니다.</p>
					""".formatted(adminOpsUrl, temporaryLoginId, temporaryCredentialExpiresAt);

		emailPort.send(new OutboundEmailMessage(
				new EmailAddress(senderAddress, "Pikume Ops"),
				new EmailAddress(email, ""),
				"[Pikume Ops] 관리자 계정 생성 안내",
				html,
				List.of()));
	}
}
