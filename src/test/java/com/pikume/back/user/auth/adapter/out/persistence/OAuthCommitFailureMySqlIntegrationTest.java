package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.adapter.out.crypto.OAuthSecretCipher;
import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.port.in.ReauthenticateUserUseCase;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.application.service.GoogleAuthenticationService;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.Status;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import com.pikume.back.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Faults only the terminal write; member/link persistence and OAuth state transitions remain real. */
@Testcontainers
@Tag("mysql-migration")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OAuthCommitFailureMySqlIntegrationTest extends SignupPersistenceTestSupport {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    OAuthAuthorizationRequestPersistenceAdapter requests;
    GoogleAuthenticationService google;
    final Map<String,String> nonces=new HashMap<>(),pkceChallenges=new HashMap<>();
    final List<String> verifiedNonces=new ArrayList<>();
    final AtomicReference<GoogleIdentity> identity=new AtomicReference<>();
    String memberId;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username",MYSQL::getUsername);
        properties.add("spring.datasource.password",MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.MySQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
        properties.add("spring.flyway.enabled",()->"true");
    }

    @BeforeEach void oauth() {
        jdbc.update("DELETE FROM oauth_authorization_requests");
        jdbc.update("DELETE FROM oauth_start_rate_limits WHERE bucket_key<>'guard'");
        memberId=tx.required(()->store.createUser(new User("existing@gmail.com","password-hash","existing",5L)).getId());
        given(policy.legacyEmailAccountsVerified()).willReturn(true);
        requests=spy(new OAuthAuthorizationRequestPersistenceAdapter(jdbc,transactionManager));
        var protocol=mock(OAuthProtocolPolicyPort.class);
        given(protocol.callerHourlyLimit()).willReturn(20);given(protocol.originHourlyLimit()).willReturn(100);
        var secrets=new OAuthSecretCipher(Base64.getEncoder().encodeToString(new byte[32]),new SecureRandom());
        identity.set(new GoogleIdentity("Subject","existing@gmail.com",true,true));
        nonces.clear();pkceChallenges.clear();verifiedNonces.clear();
        google=new GoogleAuthenticationService(requests,protocol,secrets,(state,nonce,pkce)->{
            nonces.put(state,nonce);pkceChallenges.put(state,pkce);return state;
        },(code,verifier,nonce)->{
            try {
                String pkce=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                assertThat(pkce).isEqualTo(pkceChallenges.get(code));
            } catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            return verifyIdentity(code,nonce);
        },(registration,token,nonce)->{
            assertThat(registration).isEqualTo("ios");return verifyIdentity(token,nonce);
        },service,mock(ReauthenticateUserUseCase.class));
    }

    @ParameterizedTest
    @CsvSource({"BEFORE_TERMINAL,false","BOTH_TERMINALS,false","AFTER_COMMIT,false","RESPONSE_LOST,false",
        "BEFORE_TERMINAL,true","BOTH_TERMINALS,true","AFTER_COMMIT,true","RESPONSE_LOST,true"})
    void freshAuthenticationRecoversTheCommittedLinkAfterTerminalFailure(FailurePoint point,boolean mobile) {
        String original=start(mobile);
        String requestId=jdbc.queryForObject("SELECT id FROM oauth_authorization_requests",String.class);
        var failure=new DataAccessResourceFailureException("injected terminal persistence failure");
        doAnswer(call->{
            String id=call.getArgument(0);Status status=call.getArgument(1);
            if(id.equals(requestId)&&status==Status.CONSUMED) {
                // This independent JDBC read proves the application already committed its link.
                assertThat(jdbc.queryForObject("SELECT user_id FROM user_oauth_accounts WHERE provider='GOOGLE' AND provider_subject='Subject'",String.class)).isEqualTo(memberId);
                assertThat(count("User")).isEqualTo(1);
                if(point==FailurePoint.AFTER_COMMIT)call.callRealMethod();
                if(point!=FailurePoint.RESPONSE_LOST)throw failure;
            }
            if(id.equals(requestId)&&status==Status.FAILED&&point==FailurePoint.BOTH_TERMINALS)throw failure;
            return call.callRealMethod();
        }).when(requests).finish(anyString(),any());

        if(point==FailurePoint.RESPONSE_LOST)complete(mobile,original); // Deliberately discard the successful response.
        else assertThatThrownBy(()->complete(mobile,original)).isSameAs(failure);
        String expected=point==FailurePoint.BEFORE_TERMINAL?"FAILED":point==FailurePoint.BOTH_TERMINALS?"PROCESSING":"CONSUMED";
        assertThat(jdbc.queryForObject("SELECT status FROM oauth_authorization_requests WHERE id=?",String.class,requestId)).isEqualTo(expected);
        assertThatThrownBy(()->complete(mobile,original)).isInstanceOfSatisfying(OAuthRequestException.class,
            error->assertThat(error.getReason()).isEqualTo(OAuthRequestException.Reason.REPLAY));
        assertThat(verifiedNonces).hasSize(1);

        given(policy.enabled()).willReturn(false);
        String fresh=start(mobile);
        assertThat(fresh).isNotEqualTo(original);
        GoogleAuthenticationResult recovered=complete(mobile,fresh);
        assertThat(recovered.signup().progress().userId()).isEqualTo(memberId);
        assertThat(recovered.signup().proof()).isNull();
        assertThat(verifiedNonces).hasSize(2).doesNotHaveDuplicates();
        assertThat(count("User")).isEqualTo(1);assertThat(count("UserOAuthAccount")).isEqualTo(1);
        assertThat(count("UserAgreement")).isZero();assertThat(count("SignupAuthentication")).isZero();

        given(policy.enabled()).willReturn(true);
        identity.set(new GoogleIdentity("OtherSubject","other@gmail.com",true,true));
        var different=complete(mobile,start(mobile));
        assertThat(different.signup().progress().userId()).isNull();
        assertThat(different.signup().proof()).isNotNull();
        assertThat(count("User")).isEqualTo(1);assertThat(count("UserOAuthAccount")).isEqualTo(1);
    }

    private String start(boolean mobile) {
        if(!mobile)return google.startWeb("caller","device",null,null,"origin").authorizationUrl();
        var challenge=google.startMobile("ios","caller","device",null,null,"origin");
        nonces.put(challenge.state(),challenge.nonce());return challenge.state();
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void requestExpiringDuringClaimLockWaitNeverCallsGoogle(boolean mobile) throws Exception {
        String state=start(mobile);
        Instant expires=Instant.now().plusSeconds(2);
        jdbc.update("UPDATE oauth_authorization_requests SET expires_at=?",LocalDateTime.ofInstant(expires,ZoneOffset.UTC));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        var observer=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
        Future<?> holder=workers.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            jdbc.queryForList("SELECT id FROM oauth_authorization_requests FOR UPDATE");locked.countDown();
            try {if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("gate timeout");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
        }));
        try {
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<GoogleAuthenticationResult> request=workers.submit(()->complete(mobile,state));
            await().atMost(Duration.ofSeconds(4)).until(()->observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0);
            await().atMost(Duration.ofSeconds(4)).until(()->Instant.now().isAfter(expires));
            release.countDown();holder.get(15,TimeUnit.SECONDS);
            assertThatThrownBy(()->request.get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                error->assertThat(error.getCause()).isInstanceOfSatisfying(OAuthRequestException.class,
                    oauth->assertThat(oauth.getReason()).isEqualTo(OAuthRequestException.Reason.EXPIRED)));
            assertThat(verifiedNonces).isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM oauth_authorization_requests",String.class)).isEqualTo("PENDING");
            assertThat(count("UserOAuthAccount")).isZero();
        } finally {release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }
    private GoogleAuthenticationResult complete(boolean mobile,String state) {
        return mobile?google.completeMobile(state,state,"caller"):google.completeWeb(state,state,"caller");
    }
    private GoogleIdentity verifyIdentity(String token,String nonce) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(nonce).isEqualTo(nonces.get(token));verifiedNonces.add(nonce);return identity.get();
    }
    private enum FailurePoint { BEFORE_TERMINAL, BOTH_TERMINALS, AFTER_COMMIT, RESPONSE_LOST }
}
