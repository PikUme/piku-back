package com.pikume.back.user.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.BDDMockito.given;

import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.notification.DiscordWebhookService;
import com.pikume.back.global.notification.dto.OperationalAlert;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class EmailVerificationExceptionHandlerTest {

	@Test
	void redisFailureReturnsProblemDetails503AndSendsAnOperationalAlert() {
		DiscordWebhookService discord = mock(DiscordWebhookService.class);
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("discordWebhookService", discord);
		ObjectProvider<DiscordWebhookService> discordProvider = beanFactory.getBeanProvider(DiscordWebhookService.class);
		Environment environment = mock(Environment.class);
		given(environment.getActiveProfiles()).willReturn(new String[] {"test"});
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/verify-code");
		EmailVerificationExceptionHandler handler = new EmailVerificationExceptionHandler(
				new ProblemDetailFactory(), discordProvider, environment);

		var response = handler.handle(new EmailVerificationException(
				EmailVerificationFailure.VERIFICATION_UNAVAILABLE,
				new IllegalStateException("Redis unavailable"), "code_verify"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody().getStatus()).isEqualTo(503);
		assertThat(response.getBody().getDetail()).contains("이메일 인증");
		assertThat(response.getBody().getInstance().toString()).isEqualTo("/api/auth/verify-code");
		then(discord).should().sendOperationalAlert(any(OperationalAlert.class));
	}

	@Test
	void codeMismatchReturnsBadRequestWithoutOperationalAlert() {
		ObjectProvider<DiscordWebhookService> discordProvider =
				new StaticListableBeanFactory().getBeanProvider(DiscordWebhookService.class);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/verify-code");
		EmailVerificationExceptionHandler handler = new EmailVerificationExceptionHandler(
				new ProblemDetailFactory(), discordProvider, mock(Environment.class));

		var response = handler.handle(new EmailVerificationException(EmailVerificationFailure.CODE_MISMATCH), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().getStatus()).isEqualTo(400);
	}
}
