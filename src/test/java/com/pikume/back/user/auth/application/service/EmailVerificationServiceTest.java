package com.pikume.back.user.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.auth.application.dto.EmailVerificationReservation;
import com.pikume.back.user.auth.application.dto.EmailVerificationResult;
import com.pikume.back.user.auth.application.dto.SendEmailVerificationCommand;
import com.pikume.back.user.auth.application.dto.VerifyEmailCodeCommand;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.EmailVerificationPolicyPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

	@Mock
	private EmailVerificationStorePort store;
	@Mock
	private EmailVerificationPolicyPort policy;
	@Mock
	private IssueVerificationEmailPort emailSender;
	@Mock
	private QueryAllowedEmailUseCase allowedEmails;
	@Mock
	private CheckUserUniquenessPort userUniqueness;

	private EmailVerificationService service;

	@BeforeEach
	void setUp() {
		service = new EmailVerificationService(store, policy, emailSender, allowedEmails, userUniqueness);
		given(allowedEmails.isEmailAllowed("person@example.com")).willReturn(true);
	}

	@Test
	void sendingNormalizesEmailAndActivatesTheReservedCodeAfterSmtpSucceeds() {
		given(policy.emailHourlyLimit()).willReturn(5);
		given(policy.resendSeconds()).willReturn(60);
		LocalDateTime expiresAt = LocalDateTime.of(2026, 9, 30, 12, 5);
		LocalDateTime resendAt = LocalDateTime.of(2026, 9, 30, 12, 1);
		given(userUniqueness.isEmailRegistered("person@example.com")).willReturn(false);
		given(store.prepare(eq(EmailVerificationService.hash("person@example.com")), anyString(), matches("[0-9a-f]{64}"), eq(5), eq(60)))
				.willAnswer(invocation -> new EmailVerificationReservation(
						invocation.getArgument(1), expiresAt, resendAt));
		given(store.activate(eq(EmailVerificationService.hash("person@example.com")), anyString(), matches("[0-9a-f]{64}")))
				.willReturn(true);

		var response = service.sendEmailCode(new SendEmailVerificationCommand("Person@Example.com"));

		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		then(emailSender).should().deliverVerificationCode(eq("person@example.com"), code.capture());
		assertThat(code.getValue()).matches("[0-9]{6}");
		then(store).should().prepare(eq(EmailVerificationService.hash("person@example.com")), anyString(),
					eq(EmailVerificationService.hash(code.getValue())), eq(5), eq(60));
		then(store).should().activate(eq(EmailVerificationService.hash("person@example.com")), anyString(),
				eq(EmailVerificationService.hash(code.getValue())));
		assertThat(response.expiresAt()).isEqualTo(expiresAt);
		assertThat(response.resendAvailableAt()).isEqualTo(resendAt);
	}

	@Test
	void smtpFailureKeepsTheKnownRedisResendDeadlineAndDoesNotActivateCode() {
		given(policy.emailHourlyLimit()).willReturn(5);
		given(policy.resendSeconds()).willReturn(60);
		LocalDateTime resendAt = LocalDateTime.of(2026, 9, 30, 12, 1);
		given(userUniqueness.isEmailRegistered("person@example.com")).willReturn(false);
		given(store.prepare(eq(EmailVerificationService.hash("person@example.com")), anyString(), matches("[0-9a-f]{64}"), eq(5), eq(60)))
				.willAnswer(invocation -> new EmailVerificationReservation(
						invocation.getArgument(1), resendAt.plusMinutes(4), resendAt));
		willThrow(new IllegalStateException("SMTP unavailable"))
				.given(emailSender).deliverVerificationCode(eq("person@example.com"), matches("[0-9]{6}"));

		assertThatThrownBy(() -> service.sendEmailCode(new SendEmailVerificationCommand("person@example.com")))
				.isInstanceOfSatisfying(EmailVerificationException.class, exception -> {
					assertThat(exception.getReason()).isEqualTo(EmailVerificationFailure.EMAIL_SEND_FAILED);
					assertThat(exception.getRetryAt()).isEqualTo(resendAt);
				});
		then(store).should(never()).activate(anyString(), anyString(), anyString());
	}

	@Test
	void duplicateEmailIsRejectedBeforeRedisPreparationOrEmailDelivery() {
		given(userUniqueness.isEmailRegistered("person@example.com")).willReturn(true);

		assertThatThrownBy(() -> service.sendEmailCode(new SendEmailVerificationCommand("person@example.com")))
				.isInstanceOfSatisfying(EmailVerificationException.class,
						exception -> assertThat(exception.getReason()).isEqualTo(EmailVerificationFailure.EMAIL_ALREADY_EXISTS));
		then(store).shouldHaveNoInteractions();
		then(emailSender).shouldHaveNoInteractions();
	}

	@Test
	void verificationCreatesAndReturnsOnlyTheNewRawToken() {
		given(policy.maxCodeAttempts()).willReturn(5);
		given(store.verify(eq(EmailVerificationService.hash("person@example.com")), matches("[0-9a-f]{64}"),
				matches("[0-9a-f]{64}"), matches("[A-Za-z0-9_-]{43}"), eq(5)))
				.willAnswer(invocation -> new EmailVerificationStorePort.EmailVerificationAttempt(null,
						new EmailVerificationResult(
								invocation.getArgument(3), LocalDateTime.of(2026, 9, 30, 12, 10))));

		var result = service.verifyEmailCode(new VerifyEmailCodeCommand("Person@Example.com", "123456"));

		assertThat(result.emailVerificationToken()).matches("[A-Za-z0-9_-]{43}");
		then(store).should().verify(eq(EmailVerificationService.hash("person@example.com")),
					eq(EmailVerificationService.hash("123456")), matches("[0-9a-f]{64}"),
					matches("[A-Za-z0-9_-]{43}"), eq(5));
	}

}
