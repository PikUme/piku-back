package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.adapter.out.crypto.OAuthSecretCipher;
import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.GoogleAuthenticationException;
import com.pikume.back.user.auth.application.port.in.*;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleAuthenticationServiceTest {
    final Instant now = Instant.parse("2026-09-07T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final MemoryStore store = new MemoryStore();
    final SignupFlowUseCase signup = mock(SignupFlowUseCase.class);
    final ReauthenticateUserUseCase reauthenticate = mock(ReauthenticateUserUseCase.class);
    final ExchangeGoogleCodePort exchange = mock(ExchangeGoogleCodePort.class);
    final VerifyGoogleIdentityPort verify = mock(VerifyGoogleIdentityPort.class);
    final OAuthProtocolPolicyPort policy = mock(OAuthProtocolPolicyPort.class);
    final OAuthSecretCipher cipher = new OAuthSecretCipher(Base64.getEncoder().encodeToString(new byte[32]), new SecureRandom());
    final GoogleIdentity identity = new GoogleIdentity("subject-1", "user@gmail.com", true, true);
    final SignupProofResult proof = new SignupProofResult("proof", null);
    GoogleAuthenticationService service;
    @BeforeEach void setup() {
        when(policy.callerHourlyLimit()).thenReturn(20);
        when(policy.originHourlyLimit()).thenReturn(100);
        when(exchange.exchangeCode(anyString(), anyString(), anyString())).thenReturn(identity);
        when(verify.verifyIdToken(anyString(), anyString(), anyString())).thenReturn(identity);
        when(signup.authenticateSocial(any())).thenReturn(proof);
        service = new GoogleAuthenticationService(store, policy, cipher,
            (state, nonce, challenge) -> state, exchange, verify, signup, reauthenticate, clock, new SecureRandom());
    }
    String startWeb() { return service.startWeb("caller", "device", null, null, "127.0.0.1").authorizationUrl(); }
    @Test void stateIsBoundAndExchangedOnlyOnce() {
        String state = startWeb();
        var request = store.only();
        assertThat(request.stateHash()).isNotEqualTo(state);
        assertThat(request.callerBindingHash()).isNotEqualTo("caller");
        assertThat(request.expiresAt()).isEqualTo(now.plusSeconds(600));
        assertThatThrownBy(() -> service.completeWeb(state, "code", "wrong")).isInstanceOf(OAuthRequestException.class);
        assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.PENDING);
        var result = service.completeWeb(state, "code", "caller");
        assertThat(result.signup()).isEqualTo(proof);
        assertThat(result.deviceId()).isEqualTo("device");
        assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.CONSUMED);
        assertThatThrownBy(() -> service.completeWeb(state, "code", "caller")).isInstanceOf(OAuthRequestException.class);
        verify(exchange, times(1)).exchangeCode(eq("code"), anyString(), anyString());
    }
    @Test void providerFailurePermanentlyFailsRequest() {
        String state = startWeb();
        when(exchange.exchangeCode(anyString(), anyString(), anyString()))
            .thenThrow(new GoogleAuthenticationException(GoogleAuthenticationException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> service.completeWeb(state, "code", "caller")).isInstanceOf(GoogleAuthenticationException.class);
        assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.FAILED);
        assertThatThrownBy(() -> service.completeWeb(state, "code", "caller")).isInstanceOf(OAuthRequestException.class);
        verifyNoInteractions(signup);
    }
    @Test void nativeUsesStoredRegistrationNonceTargetAndDevice() {
        var challenge = service.startMobile("ios", "caller", "ios-device", "target", "password", "127.0.0.1");
        assertThat(store.only().purpose()).isEqualTo(OAuthAuthorizationRequest.Purpose.LINK);
        assertThatThrownBy(() -> service.completeWeb(challenge.state(), "code", "caller")).isInstanceOf(OAuthRequestException.class);
        var result = service.completeMobile(challenge.state(), "id-token", "caller");
        assertThat(result.deviceId()).isEqualTo("ios-device");
        verify(reauthenticate).reauthenticate("target", "password");
        verify(verify).verifyIdToken("ios", "id-token", challenge.nonce());
        verify(signup).authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE", "subject-1", "user@gmail.com", true, true, "caller", "target"));
    }
    @Test void invalidRegistrationOrReauthenticationNeverWritesRequest() {
        doThrow(new OAuthRequestException(OAuthRequestException.Reason.INVALID_REQUEST)).when(policy).requireRegistration(OAuthAuthorizationRequest.Channel.MOBILE, "unknown");
        assertThatThrownBy(() -> service.startMobile("unknown", "caller", "device", null, null, "source")).isInstanceOf(OAuthRequestException.class);
        doThrow(new IllegalArgumentException()).when(reauthenticate).reauthenticate("target", "wrong");
        assertThatThrownBy(() -> service.startWeb("caller", "device", "target", "wrong", "source")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.requests).isEmpty();
    }
    @Test void denialCanOnlyFailMatchingPendingWebRequest() {
        String state = startWeb();
        assertThatThrownBy(() -> service.failWeb(state, "other")).isInstanceOf(OAuthRequestException.class);
        assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.PENDING);
        service.failWeb(state, "caller");
        assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.FAILED);
        verifyNoInteractions(exchange, signup);
    }
    @Test void blankDeviceOrOriginCannotAllocateRequests() {
        assertThatThrownBy(() -> service.startWeb("caller", " ", null, null, "source")).isInstanceOf(OAuthRequestException.class);
        assertThatThrownBy(() -> service.startWeb("caller", "device", null, null, " ")).isInstanceOf(OAuthRequestException.class);
        assertThat(store.requests).isEmpty();
    }
    @Test void webAuthorizationS256ChallengeMatchesTheStoredVerifierAndExpectedNonce() throws Exception {
        String[] sent = new String[3];
        var boundService = new GoogleAuthenticationService(store, policy, cipher, (state, nonce, challenge) -> {
            sent[0] = state; sent[1] = nonce; sent[2] = challenge; return state;
        }, exchange, verify, signup, reauthenticate, clock, new SecureRandom());
        String state = boundService.startWeb("caller", "device", null, null, "source").authorizationUrl();
        var request = store.only();
        String codeVerifier = cipher.decrypt(request.encryptedCodeVerifier(), request.id() + ":verifier");
        String expectedNonce = cipher.decrypt(request.encryptedNonce(), request.id() + ":nonce");
        assertThat(state).matches("[A-Za-z0-9_-]{43}");
        assertThat(expectedNonce).isEqualTo(sent[1]).isNotEqualTo(state).matches("[A-Za-z0-9_-]{43}");
        assertThat(codeVerifier).isNotEqualTo(state).isNotEqualTo(expectedNonce).matches("[A-Za-z0-9_-]{43}");
        assertThat(sent[2]).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        boundService.completeWeb(state, "code", "caller");
        verify(exchange).exchangeCode("code", codeVerifier, expectedNonce);
    }
    @Test void coreFailureAlsoPermanentlyFailsClaimedRequest() {
        String state = startWeb();
        when(signup.authenticateSocial(any())).thenThrow(new IllegalStateException("core unavailable"));
        assertThatThrownBy(() -> service.completeWeb(state, "code", "caller")).isInstanceOf(IllegalStateException.class);
        assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.FAILED);
        assertThatThrownBy(() -> service.completeWeb(state, "code", "caller")).isInstanceOf(OAuthRequestException.class);
    }
    @Test void externalExchangeRunsAfterClaimCommitOutsideEvenAnAmbientTransaction() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:oauth_tx;DB_CLOSE_DELAY=-1", "sa", "");
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        GoogleAuthenticationUseCase useCase = (GoogleAuthenticationUseCase) proxy.getProxy();
        String state = startWeb();
        when(exchange.exchangeCode(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(store.only().status()).isEqualTo(OAuthAuthorizationRequest.Status.PROCESSING);
            return identity;
        });
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(useCase.completeWeb(state, "code", "caller").signup()).isEqualTo(proof);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
    }
    static class MemoryStore implements OAuthAuthorizationRequestStorePort {
        final Map<String, OAuthAuthorizationRequest> requests = new HashMap<>();
        public void create(OAuthAuthorizationRequest request, String originHash, int callerLimit, int originLimit) { requests.put(request.stateHash(), request); }
        public synchronized OAuthAuthorizationRequest claim(String hash, String bindingHash, OAuthAuthorizationRequest.Channel channel, Clock clock) {
            var request = requests.get(hash);
            if (request == null) throw new OAuthRequestException(OAuthRequestException.Reason.NOT_FOUND);
            request.requireClaimable(bindingHash, channel, clock.instant());
            var claimed = request.withStatus(OAuthAuthorizationRequest.Status.PROCESSING);
            requests.put(hash, claimed);
            return claimed;
        }
        public void finish(String id, OAuthAuthorizationRequest.Status status) { requests.replaceAll((key, r) -> r.id().equals(id) ? r.withStatus(status) : r); }
        public int cleanup(Instant now, int limit) { return 0; }
        OAuthAuthorizationRequest only() { return requests.values().iterator().next(); }
    }
}
