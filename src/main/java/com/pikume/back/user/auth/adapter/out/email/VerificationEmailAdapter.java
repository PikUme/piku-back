package com.pikume.back.user.auth.adapter.out.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.pikume.back.global.email.EmailAddress;
import com.pikume.back.global.email.OutboundEmailMessage;
import com.pikume.back.global.port.out.SendOutboundEmailPort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.constants.EmailConstants;
import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;

import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class VerificationEmailAdapter implements IssueVerificationEmailPort {

	private final SendOutboundEmailPort emailPort;

	@Value("${outbound-email.from-address}")
	private String senderAddress;

	@Override
	public String issueVerificationEmail(String email) {
		String code = createVerificationCode();
		deliverVerificationCode(email, code);
		return code;
	}

	@Override
	public void deliverVerificationCode(String email, String code) {
		OutboundEmailMessage message = new OutboundEmailMessage(
				new EmailAddress(senderAddress, "PikUme | 피쿠"),
				new EmailAddress(email, ""),
				"[PikUme] 이메일 인증",
				String.format(EmailConstants.AUTH_CODE_CONTENT, code),
				List.of());
		try {
			emailPort.send(message);
		} catch (RuntimeException e) {
			throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILURE);
		}
	}

	private String createVerificationCode() {
		Random random = new Random();
		int code = 100000 + random.nextInt(900000);
		return String.valueOf(code);
	}
}
