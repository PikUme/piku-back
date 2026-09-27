package com.pikume.back.user.auth.adapter.out.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.constants.EmailConstants;
import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;

@Component
@RequiredArgsConstructor
public class SmtpEmailAdapter implements IssueVerificationEmailPort {

	private final JavaMailSender mailSender;

	@Value("${spring.mail.username}")
	private String adminEmail;

	@Override
	public String issueVerificationEmail(String email) {
		String code = createVerificationCode();
		String subject = "[PikUme] 이메일 인증";

		try {
			MimeMessage mimeMessage = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");

			String htmlContent = String.format(EmailConstants.AUTH_CODE_CONTENT, code);

			helper.setFrom(adminEmail, "PikUme | 피쿠");
			helper.setTo(email);
			helper.setSubject(subject);
			helper.setText(htmlContent, true);

			mailSender.send(mimeMessage);
		} catch (MessagingException | UnsupportedEncodingException e) {
			throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILURE);
		} catch (RuntimeException e) {
			throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILURE);
		}

		return code;
	}

	private String createVerificationCode() {
		SecureRandom random = new SecureRandom();
		int code = 100000 + random.nextInt(900000);
		return String.valueOf(code);
	}
}
