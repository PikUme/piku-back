package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.exception.GlobalExceptionHandler;
import com.pikume.back.global.notification.DiscordWebhookService;
import com.pikume.back.global.notification.dto.OperationalAlert;
import com.pikume.back.user.auth.application.exception.AuthErrorCode;
import com.pikume.back.user.auth.application.exception.AuthException;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailVerificationExceptionHandlerTest {

	@Test
	void smtpFailureCauseUsesEmailVerificationAdviceBeforeAuthAdvice() throws Exception {
		DiscordWebhookService discord = mock(DiscordWebhookService.class);
		MockMvc mockMvc = mockMvc(discord);

		mockMvc.perform(post("/test/smtp-failure"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value(
						"https://api.pikume.com/problems/email-verification/email-send-failed"))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.instance").value("/test/smtp-failure"));

		then(discord).shouldHaveNoInteractions();
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

	@Test
	void rateLimitReturnsRetryTimeInProblemBodyAndRetryAfterHeader() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
				.setControllerAdvice(new EmailVerificationExceptionHandler(
						new ProblemDetailFactory(), new StaticListableBeanFactory()
								.getBeanProvider(DiscordWebhookService.class), mock(Environment.class)))
				.build();
		LocalDateTime retryAt = LocalDateTime.of(2030, 1, 2, 3, 4, 5);

		mockMvc.perform(post("/test/rate-limited"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.resendAvailableAt").value("2030-01-02T03:04:05"))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void exhaustedAttemptsReturnTooManyRequestsWithRetryTime() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
				.setControllerAdvice(new EmailVerificationExceptionHandler(
						new ProblemDetailFactory(), new StaticListableBeanFactory()
								.getBeanProvider(DiscordWebhookService.class), mock(Environment.class)))
				.build();

		mockMvc.perform(post("/test/attempts-exhausted"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ATTEMPTS_EXHAUSTED"))
				.andExpect(jsonPath("$.resendAvailableAt").exists())
				.andExpect(header().exists("Retry-After"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"connect", "socket-reset"})
	void redisFailureCauseUsesEmailVerificationAdviceAndSendsOperationalAlert(String causeType) throws Exception {
		DiscordWebhookService discord = mock(DiscordWebhookService.class);
		MockMvc mockMvc = mockMvc(discord);

		mockMvc.perform(post("/test/redis-failure/" + causeType))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value(
						"https://api.pikume.com/problems/email-verification/verification-unavailable"))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.instance").value("/test/redis-failure/" + causeType))
				.andExpect(jsonPath("$.code").value("VERIFICATION_UNAVAILABLE"));

		then(discord).should().sendOperationalAlert(any(OperationalAlert.class));
		then(discord).should(never()).sendExceptionNotification(any(Exception.class), any());
	}

	private MockMvc mockMvc(DiscordWebhookService discord) {
		ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("discordWebhookService", discord);
		ObjectProvider<DiscordWebhookService> discordProvider = beanFactory.getBeanProvider(DiscordWebhookService.class);
		Environment environment = mock(Environment.class);
		given(environment.getActiveProfiles()).willReturn(new String[] {"test"});
		EmailVerificationExceptionHandler emailVerificationExceptionHandler =
				new EmailVerificationExceptionHandler(problemDetailFactory, discordProvider, environment);

		return MockMvcBuilders.standaloneSetup(new FailureController())
				.setControllerAdvice(
						new GlobalExceptionHandler(Optional.of(discord), problemDetailFactory),
						new AuthExceptionHandler(problemDetailFactory),
						emailVerificationExceptionHandler)
				.build();
	}

	@RestController
	static class FailureController {

		@PostMapping("/test/rate-limited")
		void rateLimited() {
			throw new EmailVerificationException(EmailVerificationFailure.RATE_LIMITED,
					LocalDateTime.of(2030, 1, 2, 3, 4, 5));
		}

		@PostMapping("/test/attempts-exhausted")
		void attemptsExhausted() {
			throw new EmailVerificationException(EmailVerificationFailure.ATTEMPTS_EXHAUSTED,
					LocalDateTime.now().plusMinutes(5));
		}

		@PostMapping("/test/smtp-failure")
		void smtpFailure() {
			throw new EmailVerificationException(
					EmailVerificationFailure.EMAIL_SEND_FAILED,
					new AuthException(AuthErrorCode.EMAIL_SEND_FAILURE),
					null);
		}

		@PostMapping("/test/redis-failure/{causeType}")
		void redisFailure(@PathVariable String causeType) {
			IOException transportFailure = switch (causeType) {
				case "socket-reset" -> new SocketException("Connection reset");
				default -> new ConnectException("connection refused");
			};
			throw new EmailVerificationException(
					EmailVerificationFailure.VERIFICATION_UNAVAILABLE,
					new RedisConnectionFailureException("Redis connection failed", transportFailure),
					"code_verify");
		}
	}
}
