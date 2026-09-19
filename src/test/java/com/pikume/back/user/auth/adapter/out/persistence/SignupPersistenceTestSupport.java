package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.*;
import com.pikume.back.user.auth.application.port.in.*;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.application.service.SignupFlowService;
import com.pikume.back.user.auth.domain.*;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.service.PasswordPolicy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({SignupPersistenceAdapter.class,SignupTransactionAdapter.class,SignupFlowService.class,PasswordPolicy.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
abstract class SignupPersistenceTestSupport {
 @Autowired SignupFlowService service;
 @Autowired SignupStorePort store;
 @Autowired SignupTransactionPort tx;
 @Autowired EntityManager em;
 @MockitoBean SignupPolicyPort policy;
 @MockitoBean PasswordProtectionPort passwords;
 @MockitoBean IssueVerificationEmailPort sender;
 @MockitoBean ResolveDefaultSignupCharacterPort characters;
 @MockitoBean SignUpUseCase legacy;
 @MockitoBean QueryAllowedEmailUseCase allowed;
 final List<AgreementAcceptance> agreements=List.of(new AgreementAcceptance("TERMS","v1",true));
 @BeforeEach void setup() {
  tx.required(() -> {
   em.createQuery("delete from UserAgreement").executeUpdate();em.createQuery("delete from UserOAuthAccount").executeUpdate();
   em.createQuery("delete from SignupAuthentication").executeUpdate();em.createQuery("delete from Verification").executeUpdate();
   em.createQuery("delete from User").executeUpdate();em.createQuery("delete from SignupRateLimit").executeUpdate();
   em.persist(new SignupRateLimit("guard",Instant.EPOCH));return null;
  });
  when(policy.enabled()).thenReturn(true);when(policy.maxCodeAttempts()).thenReturn(2);when(policy.resendSeconds()).thenReturn(60);
  when(policy.emailHourlyLimit()).thenReturn(5);when(policy.originHourlyLimit()).thenReturn(30);
  when(policy.agreements()).thenReturn(List.of(new SignupAgreementDocument("TERMS","v1","immutable actual content",true)));
  when(characters.resolveDefaultSignupCharacter()).thenReturn(5L);when(allowed.isEmailAllowed(anyString())).thenReturn(true);
  when(sender.issueVerificationEmail(anyString())).thenReturn("123456");when(passwords.protect(anyString())).thenReturn("password-hash");
 }
 String emailProof(String email) {
  String raw=UUID.randomUUID().toString();
  tx.required(() -> {store.saveProof(SignupAuthentication.email(SignupFlowService.hash(raw),SignupFlowService.hash("caller"),email,"password-hash",Instant.now()));return null;});
  return raw;
 }
 long count(String entity) {return tx.required(() -> em.createQuery("select count(e) from "+entity+" e",Long.class).getSingleResult());}
}
