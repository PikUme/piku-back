package com.pikume.back.user.adapter.in.web;

import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.user.application.dto.NicknameReservationResult;
import com.pikume.back.user.auth.adapter.in.web.SignupNicknameReservationController;
import com.pikume.back.user.auth.adapter.in.web.EmailVerificationExceptionHandler;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.global.notification.DiscordWebhookService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.Environment;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.application.exception.NicknameReservationTokenInvalidException;
import com.pikume.back.user.application.exception.NicknameReservationUnavailableException;
import com.pikume.back.user.application.port.in.ReserveSignupNicknameUseCase;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.NicknameIdentityPort;
import com.pikume.back.user.application.port.out.NicknameReservationStorePort;
import com.pikume.back.user.application.port.out.NicknameWriteTransactionPort;
import com.pikume.back.user.application.service.SignupNicknameReservationService;
import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupNicknameReservationControllerTest {

	private ReserveSignupNicknameUseCase useCase;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		useCase = mock(ReserveSignupNicknameUseCase.class);
		ProblemDetailFactory problems = new ProblemDetailFactory();
		mockMvc = MockMvcBuilders.standaloneSetup(new SignupNicknameReservationController(useCase))
				.setControllerAdvice(new NicknameReservationExceptionHandler(problems), emailVerificationHandler(problems))
				.build();
	}

	@Test
	void returnsNicknameAndExpiry() throws Exception {
		LocalDateTime expiresAt = LocalDateTime.of(2026, 10, 9, 12, 0);
		given(useCase.reserve("member@example.com", "proof-token", "Nickname"))
				.willReturn(new NicknameReservationResult("Nickname", "nickname-key", "version-1", expiresAt));

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"member@example.com","emailVerificationToken":"proof-token","nickname":"Nickname"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("Nickname"))
				.andExpect(jsonPath("$.expiresAt").value("2026-10-09T12:00:00"));
	}

	@Test
	void reportsInvalidEmailThroughExistingEmailProblemDetails() throws Exception {
		given(useCase.reserve("invalid", "proof-token", "Nickname"))
				.willThrow(new EmailVerificationException(EmailVerificationFailure.INVALID_EMAIL));

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"invalid","emailVerificationToken":"proof-token","nickname":"Nickname"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
	}

	@Test
	void reportsInvalidProofAsProblemDetail() throws Exception {
		given(useCase.reserve("member@example.com", "expired", "Nickname"))
				.willThrow(new NicknameReservationTokenInvalidException());

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"member@example.com","emailVerificationToken":"expired","nickname":"Nickname"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	void reportsReservationStoreFailureAsServiceUnavailableProblemDetail() throws Exception {
		given(useCase.reserve("member@example.com", "proof-token", "Nickname"))
				.willThrow(new NicknameReservationUnavailableException("reservation", new IllegalStateException()));

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"member@example.com","emailVerificationToken":"proof-token","nickname":"Nickname"}
						"""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.code").value("NICKNAME_RESERVATION_UNAVAILABLE"));
	}

	@Test
	void mapsDomainReservationConflictThroughUseCaseTo409() throws Exception {
		EmailVerificationStorePort proofs = mock(EmailVerificationStorePort.class);
		CheckUserUniquenessPort users = mock(CheckUserUniquenessPort.class);
		NicknameIdentityPort identities = mock(NicknameIdentityPort.class);
		NicknameReservationStorePort reservations = mock(NicknameReservationStorePort.class);
		NicknameWriteTransactionPort transactions = new NicknameWriteTransactionPort() {
			@Override
			public <T> T execute(java.util.function.Supplier<T> operation) {
				return operation.get();
			}
		};
		String emailKey = EmailVerificationService.hash("member@example.com");
		String tokenHash = EmailVerificationService.hash("proof-token");
		when(proofs.loadProof(emailKey, tokenHash)).thenReturn(java.util.Optional.of(
				new SignupEmailProof("proof-version", tokenHash, LocalDateTime.now().plusMinutes(5))));
		when(users.isEmailRegistered("member@example.com")).thenReturn(false);
		when(users.isNicknameInUse(new com.pikume.back.user.domain.vo.Nickname("Nickname"))).thenReturn(false);
		when(identities.keyFor(new com.pikume.back.user.domain.vo.Nickname("Nickname"))).thenReturn("weight-key");
		when(reservations.reserve("signup:" + emailKey, "weight-key", "Nickname"))
				.thenThrow(new NicknameReservationConflictException());
		mockMvc = MockMvcBuilders.standaloneSetup(new SignupNicknameReservationController(
				new SignupNicknameReservationService(proofs, users, identities, reservations, transactions)))
				.setControllerAdvice(new NicknameReservationExceptionHandler(new ProblemDetailFactory()))
				.build();

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"member@example.com","emailVerificationToken":"proof-token","nickname":"Nickname"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NICKNAME_CONFLICT"));
	}

	@Test
	void proofStoreOutageUsesExistingEmailVerification503AndAlertPath() throws Exception {
		EmailVerificationStorePort proofs = mock(EmailVerificationStorePort.class);
		CheckUserUniquenessPort users = mock(CheckUserUniquenessPort.class);
		NicknameIdentityPort identities = mock(NicknameIdentityPort.class);
		NicknameReservationStorePort reservations = mock(NicknameReservationStorePort.class);
		NicknameWriteTransactionPort transactions = new NicknameWriteTransactionPort() {
			@Override
			public <T> T execute(java.util.function.Supplier<T> operation) {
				return operation.get();
			}
		};
		when(proofs.loadProof(any(), any())).thenThrow(new EmailVerificationException(
				EmailVerificationFailure.VERIFICATION_UNAVAILABLE, new IllegalStateException("redis"), "proof_load"));
		DiscordWebhookService alert = mock(DiscordWebhookService.class);
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("discordWebhookService", alert);
		ObjectProvider<DiscordWebhookService> provider = beans.getBeanProvider(DiscordWebhookService.class);
		Environment environment = mock(Environment.class);
		given(environment.getActiveProfiles()).willReturn(new String[] {"test"});
		ProblemDetailFactory problems = new ProblemDetailFactory();
		mockMvc = MockMvcBuilders.standaloneSetup(new SignupNicknameReservationController(
				new SignupNicknameReservationService(proofs, users, identities, reservations, transactions)))
				.setControllerAdvice(new NicknameReservationExceptionHandler(problems),
						new EmailVerificationExceptionHandler(problems, provider, environment))
				.build();

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"member@example.com","emailVerificationToken":"proof-token","nickname":"Nickname"}
						"""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("VERIFICATION_UNAVAILABLE"));
		verify(alert).sendOperationalAlert(any());
	}

	private EmailVerificationExceptionHandler emailVerificationHandler(ProblemDetailFactory problems) {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		Environment environment = mock(Environment.class);
		given(environment.getActiveProfiles()).willReturn(new String[] {"test"});
		return new EmailVerificationExceptionHandler(problems,
				beans.getBeanProvider(DiscordWebhookService.class), environment);
	}

	@Test
	void reportsNicknameConflictAsProblemDetail() throws Exception {
		given(useCase.reserve("member@example.com", "proof-token", "Nickname"))
				.willThrow(new NicknameReservationConflictException());

		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"member@example.com","emailVerificationToken":"proof-token","nickname":"Nickname"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
	}
}
