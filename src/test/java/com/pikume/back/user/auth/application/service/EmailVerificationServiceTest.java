package com.pikume.back.user.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;

import com.pikume.back.user.auth.application.dto.SignupEmailProof;
import com.pikume.back.user.auth.application.dto.SignupVerificationSent;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;

class EmailVerificationServiceTest {

	private EmailVerificationStorePort store;
	private IssueVerificationEmailPort emailSender;
	private QueryAllowedEmailUseCase allowedEmails;
	private EmailVerificationService service;

	@BeforeEach
	void setUp() {
		store = mock(EmailVerificationStorePort.class);
		emailSender = mock(IssueVerificationEmailPort.class);
		allowedEmails = mock(QueryAllowedEmailUseCase.class);
		service = new EmailVerificationService(store, emailSender, allowedEmails);
	}

	@Test
	void sendActivatesOnlyTheGenerationWhoseEmailWasDelivered() {
		given(allowedEmails.isEmailAllowed("user@example.com")).willReturn(true);
		given(store.reserve(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
				.willReturn(new EmailVerificationStorePort.ReservationResult(
						EmailVerificationStorePort.ReservationStatus.RESERVED,
						LocalDateTime.of(2026, 10, 4, 12, 5), LocalDateTime.of(2026, 10, 4, 12, 1)));
		given(store.activate(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.anyString()))
				.willReturn(Optional.of(LocalDateTime.of(2026, 10, 4, 12, 5)));

		SignupVerificationSent sent = service.sendSignUpVerificationEmail("user@example.com");
		assertThat(sent.expiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 12, 5));
		assertThat(sent.resendAvailableAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 12, 1));

		then(store).should().activate(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.anyString());
		then(emailSender).should().deliverVerificationCode(ArgumentMatchers.eq("user@example.com"),
				ArgumentMatchers.matches("\\d{6}"));
	}

	@Test
	void failedDeliveryDoesNotActivateTheReservedCode() {
		given(allowedEmails.isEmailAllowed("user@example.com")).willReturn(true);
		given(store.reserve(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
				.willReturn(new EmailVerificationStorePort.ReservationResult(
						EmailVerificationStorePort.ReservationStatus.RESERVED,
						LocalDateTime.now().plusMinutes(5), LocalDateTime.now().plusMinutes(1)));
		BDDMockito.willThrow(new EmailVerificationException(EmailVerificationFailure.EMAIL_SEND_FAILED))
				.given(emailSender).deliverVerificationCode(ArgumentMatchers.eq("user@example.com"),
						ArgumentMatchers.anyString());

		assertThatThrownBy(() -> service.sendSignUpVerificationEmail("user@example.com"))
				.isInstanceOf(EmailVerificationException.class);

		then(store).should(never()).activate(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.anyString());
	}

	@Test
	void verificationReturnsPublicTokenAndExpiryWhilePersistingOnlyItsHash() {
		given(allowedEmails.isEmailAllowed("user@example.com")).willReturn(true);
		given(store.verify(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.eq(EmailVerificationService.hash("123456")),
				ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
				.willAnswer(invocation -> new EmailVerificationStorePort.VerificationResult(
						EmailVerificationStorePort.VerificationStatus.VERIFIED,
						new SignupEmailProof(
								invocation.getArgument(2), invocation.getArgument(3), LocalDateTime.now().plusMinutes(10)),
						null));

		var verification = service.verifySignUpVerificationCode("user@example.com", "123456");
		assertThat(verification.token()).isNotBlank();
		assertThat(verification.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(9));

		then(store).should().verify(ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				ArgumentMatchers.eq(EmailVerificationService.hash("123456")),
				ArgumentMatchers.anyString(),
				ArgumentMatchers.eq(EmailVerificationService.hash(verification.token())));
		then(emailSender).shouldHaveNoInteractions();
	}

	@Test
	void invalidEmailNeverReservesOrSendsACode() {
		assertThatThrownBy(() -> service.sendSignUpVerificationEmail("invalid"))
				.isInstanceOf(EmailVerificationException.class)
				.satisfies(error -> assertThat(((EmailVerificationException) error).getReason())
						.isEqualTo(EmailVerificationFailure.INVALID_EMAIL));

		then(store).shouldHaveNoInteractions();
		then(emailSender).shouldHaveNoInteractions();
	}

	@Test
	void disallowedEmailNeverReservesOrSendsACode() {
		given(allowedEmails.isEmailAllowed("user@example.com")).willReturn(false);

		assertThatThrownBy(() -> service.sendSignUpVerificationEmail("user@example.com"))
				.isInstanceOf(EmailVerificationException.class);

		then(store).shouldHaveNoInteractions();
		then(emailSender).shouldHaveNoInteractions();
	}
}
