package com.pikume.back.user.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.mockito.ArgumentCaptor;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.notification.DiscordWebhookService;
import com.pikume.back.global.notification.dto.OperationalAlert;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.in.ResetPasswordUseCase;
import com.pikume.back.user.auth.application.port.in.SignUpUseCase;
import com.pikume.back.user.auth.application.port.in.VerifyEmailUseCase;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmailVerificationExceptionHandlerTest {

	private EmailVerificationUseCase emailVerification;
	private DiscordWebhookService discord;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		SignUpUseCase signup = mock(SignUpUseCase.class);
		VerifyEmailUseCase passwordReset = mock(VerifyEmailUseCase.class);
		ResetPasswordUseCase resetPassword = mock(ResetPasswordUseCase.class);
		QueryAllowedEmailUseCase allowedEmails = mock(QueryAllowedEmailUseCase.class);
		emailVerification = mock(EmailVerificationUseCase.class);
		discord = mock(DiscordWebhookService.class);
		Environment environment = mock(Environment.class);
		given(environment.getActiveProfiles()).willReturn(new String[] {"prod"});
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("discordWebhookService", discord);
		ObjectProvider<DiscordWebhookService> provider = beans.getBeanProvider(DiscordWebhookService.class);
		AuthController controller = new AuthController(signup, passwordReset, resetPassword, allowedEmails, emailVerification);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setMessageConverters(new MappingJackson2HttpMessageConverter(
						Jackson2ObjectMapperBuilder.json()
								.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
								.build()))
				.setControllerAdvice(new EmailVerificationExceptionHandler(
						new ProblemDetailFactory(), provider, environment))
				.build();
	}

	@Test
	void rateLimitReturnsRetryDeadlineAndDoesNotSendRedisOutageAlert() throws Exception {
		LocalDateTime retryAt = LocalDateTime.of(2026, 9, 30, 18, 5);
		given(emailVerification.sendEmailCode(any()))
				.willThrow(new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED, retryAt));

		mvc.perform(post("/api/auth/send-verification/sign-up")
					.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"user@example.com\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.resendAvailableAt").value("2026-09-30T18:05:00"))
				.andExpect(header().string("Retry-After", "Wed, 30 Sep 2026 09:05:00 GMT"));

		verifyNoInteractions(discord);
	}

	@Test
	void lockedCodeReturns429WithoutSendingRedisOutageAlert() throws Exception {
		given(emailVerification.verifyEmailCode(any()))
				.willThrow(new EmailVerificationException(EmailVerificationFailure.ATTEMPTS_EXHAUSTED));

		mvc.perform(post("/api/auth/verify-code")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"user@example.com\",\"code\":\"123456\",\"type\":\"SIGN_UP\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ATTEMPTS_EXHAUSTED"));

		verifyNoInteractions(discord);
	}

	@Test
	void redisUnavailableReturns503AndRequestsOneAlertEvenWhenDiscordCallFails() throws Exception {
		given(emailVerification.sendEmailCode(any())).willThrow(new EmailVerificationException(
				EmailVerificationFailure.VERIFICATION_UNAVAILABLE, new IllegalStateException("redis details"), "send_prepare"));
		willThrow(new IllegalStateException("discord failed")).given(discord).sendOperationalAlert(any(OperationalAlert.class));

		mvc.perform(post("/api/auth/send-verification/sign-up")
					.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"user@example.com\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("VERIFICATION_UNAVAILABLE"));

		var alert = ArgumentCaptor.forClass(OperationalAlert.class);
		verify(discord).sendOperationalAlert(alert.capture());
		assertThat(alert.getValue().processingStage()).isEqualTo("send_prepare");
		assertThat(alert.getValue().requestPath()).isEqualTo("/api/auth/send-verification/sign-up");
		assertThat(alert.getValue().responseStatus()).isEqualTo(503);
	}

	@Test
	void smtpFailureReturns503WithoutRedisOutageAlert() throws Exception {
		given(emailVerification.sendEmailCode(any()))
				.willThrow(new EmailVerificationException(EmailVerificationFailure.EMAIL_SEND_FAILED));

		mvc.perform(post("/api/auth/send-verification/sign-up")
					.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"user@example.com\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("EMAIL_SEND_FAILED"));

		verifyNoInteractions(discord);
	}

	@Test
	void codeMismatchReturns400WithoutRedisOutageAlert() throws Exception {
		given(emailVerification.verifyEmailCode(any()))
				.willThrow(new EmailVerificationException(EmailVerificationFailure.CODE_MISMATCH));

		mvc.perform(post("/api/auth/verify-code")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"user@example.com\",\"code\":\"123456\",\"type\":\"SIGN_UP\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CODE_MISMATCH"));

		verifyNoInteractions(discord);
	}
}
