package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({EmailVerificationPersistenceAdapter.class, EmailVerificationTransactionAdapter.class, EmailVerificationService.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class EmailVerificationPersistenceIntegrationTest {

	@Autowired
	EmailVerificationService service;

	@Autowired
	EmailVerificationStorePort store;

	@Autowired
	EmailVerificationTransactionPort tx;

	@Autowired
	EntityManager em;

	@Autowired
	VerificationJpaRepository verifications;

	@MockitoBean
	EmailVerificationPolicyPort policy;

	@MockitoBean
	IssueVerificationEmailPort sender;

	@MockitoBean
	QueryAllowedEmailUseCase allowed;

	@BeforeEach
	void setup() {
		tx.required(() -> {
			em.createQuery("delete from Verification").executeUpdate();
			em.createQuery("delete from EmailVerificationRateLimit").executeUpdate();
			em.persist(new EmailVerificationRateLimit("guard",LocalDateTime.of(1970, 1, 1, 0, 0)));return null;
		});
		when(policy.maxCodeAttempts()).thenReturn(5);
		when(policy.resendSeconds()).thenReturn(60);
		when(policy.emailHourlyLimit()).thenReturn(5);when(policy.originHourlyLimit()).thenReturn(30);
		when(allowed.isEmailAllowed(anyString())).thenReturn(true);
		when(sender.issueVerificationEmail(anyString())).thenReturn("123456");
	}
	long count(String entity) {
		return tx.required(() -> em.createQuery("select count(e) from "+entity+" e",Long.class).getSingleResult());
	}

	@Test
	void changingVerificationIdentifierOrOriginCannotBypassEmailResendCooldown() {
		service.sendEmailCode(new SendEmailVerificationCommand("a@gmail.com","origin"));
		assertThatThrownBy(() -> service.sendEmailCode(new SendEmailVerificationCommand("A@gmail.com","other-origin")))
				.isInstanceOf(EmailVerificationException.class).extracting("reason").isEqualTo(EmailVerificationFailure.RATE_LIMITED);
		verify(sender,times(1)).issueVerificationEmail(anyString());
	}

	@Test
	void failedMailLeavesReservedRateButUnusableVerification() {
		when(sender.issueVerificationEmail(anyString())).thenThrow(new IllegalStateException("delivery failed"));
		assertThatThrownBy(() -> service.sendEmailCode(new SendEmailVerificationCommand("a@gmail.com","origin")))
				.isInstanceOf(EmailVerificationException.class).extracting("reason").isEqualTo(EmailVerificationFailure.EMAIL_SEND_FAILED);
		assertThat(count("Verification")).isEqualTo(1);
		assertThat((LocalDateTime) tx.required(() -> em.createQuery("select v from Verification v",Verification.class).getSingleResult().getDeliveryCompletedAt())).isNull();
	}

	@Test
	void cleanupCannotRemoveARecentSendCooldownAtHourlyWindowBoundary() {
		LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		tx.required(() -> {
			var bucket=new EmailVerificationRateLimit("email:"+EmailVerificationService.hash("a@gmail.com"),now.minusSeconds(3601));
			bucket.increment(now);em.persist(bucket);return null;
		});
		store.purgeExpired(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
		assertThatThrownBy(() -> service.sendEmailCode(new SendEmailVerificationCommand("a@gmail.com","origin")))
				.isInstanceOf(EmailVerificationException.class).extracting("reason").isEqualTo(EmailVerificationFailure.RATE_LIMITED);
	}

	@Test
	void deliveredCodeIsHashedAndUsableUntilFiveMinuteExpiry() {
		var result=service.sendEmailCode(new SendEmailVerificationCommand("a@gmail.com","origin"));
		tx.required(()->{
			Verification verification=store.lockLatestVerification("a@gmail.com").orElseThrow();
			assertThat(verification.getCode()).isNotEqualTo("123456");
			assertThat(verification.validateCode("123456",LocalDateTime.now(ZoneId.of("Asia/Seoul")),2)).isNull();
			assertThat(result.expiresAt()).isEqualTo(verification.getSentAt().plusSeconds(300));
			assertThat(result.resendAvailableAt()).isEqualTo(verification.getSentAt().plusSeconds(60));
			return null;
		});
	}

	@Test
	void verificationRowsDoNotShadowLegacySignupOrPasswordResetVerification() {
		service.sendEmailCode(new SendEmailVerificationCommand("a@gmail.com","origin"));
		tx.required(()->{
			em.persist(new Verification("a@gmail.com","654321",VerificationType.SIGN_UP,LocalDateTime.now().plusMinutes(5)));
			em.persist(new Verification("a@gmail.com","777777",VerificationType.PASSWORD_RESET,LocalDateTime.now().plusMinutes(5)));
			return null;
		});
		assertThat(verifications.findByEmailAndType("a@gmail.com",VerificationType.SIGN_UP).orElseThrow().getCode()).isEqualTo("654321");
		assertThat(verifications.findByEmailAndType("a@gmail.com",VerificationType.PASSWORD_RESET).orElseThrow().getCode()).isEqualTo("777777");
	}

	@Test
	void cleanupPreservesLegacyVerificationWhileRemovingExpiredVerifications() {
		tx.required(()->{
			store.saveVerification(Verification.emailVerification("expired","a@gmail.com",LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(301),60));
			em.persist(new Verification("legacy@gmail.com","123456",VerificationType.SIGN_UP,LocalDateTime.now().minusMinutes(1)));
			return null;
		});
		store.purgeExpired(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
		assertThat(count("Verification")).isEqualTo(1);
		assertThat(verifications.findByEmailAndType("legacy@gmail.com",VerificationType.SIGN_UP)).isPresent();
	}
}
