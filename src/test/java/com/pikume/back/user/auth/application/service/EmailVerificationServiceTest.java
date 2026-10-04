package com.pikume.back.user.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;

import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
		given(store.reserve(org.mockito.ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
				.willReturn(true);
		given(store.activate(org.mockito.ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				org.mockito.ArgumentMatchers.anyString()))
				.willReturn(true);

		service.sendSignUpVerificationEmail("user@example.com");

		then(store).should().activate(org.mockito.ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				org.mockito.ArgumentMatchers.anyString());
		then(emailSender).should().deliverVerificationCode(org.mockito.ArgumentMatchers.eq("user@example.com"),
				org.mockito.ArgumentMatchers.matches("\\d{6}"));
	}

	@Test
	void failedDeliveryDoesNotActivateTheReservedCode() {
		given(allowedEmails.isEmailAllowed("user@example.com")).willReturn(true);
		given(store.reserve(org.mockito.ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
				.willReturn(true);
		org.mockito.BDDMockito.willThrow(new EmailVerificationException(EmailVerificationFailure.EMAIL_SEND_FAILED))
				.given(emailSender).deliverVerificationCode(org.mockito.ArgumentMatchers.eq("user@example.com"),
						org.mockito.ArgumentMatchers.anyString());

		assertThatThrownBy(() -> service.sendSignUpVerificationEmail("user@example.com"))
				.isInstanceOf(EmailVerificationException.class);

		then(store).should(never()).activate(org.mockito.ArgumentMatchers.eq(EmailVerificationService.hash("user@example.com")),
				org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void verificationReturnsAnEmailBasedProofAndDoesNotIssuePublicToken() {
		given(allowedEmails.isEmailAllowed("user@example.com")).willReturn(true);
		given(store.verify(EmailVerificationService.hash("user@example.com"), EmailVerificationService.hash("123456")))
				.willReturn(EmailVerificationStorePort.VerificationResult.VERIFIED);

		service.verifySignUpVerificationCode("user@example.com", "123456");

		then(store).should().verify(EmailVerificationService.hash("user@example.com"),
				EmailVerificationService.hash("123456"));
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
