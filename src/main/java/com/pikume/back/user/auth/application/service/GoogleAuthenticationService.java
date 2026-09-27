package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.port.in.*;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.*;
import static com.pikume.back.user.auth.domain.exception.OAuthRequestException.Reason.*;

/** External verification never runs inside an ambient database transaction. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class GoogleAuthenticationService implements GoogleAuthenticationUseCase {
    private final OAuthAuthorizationRequestStorePort store;
    private final OAuthProtocolPolicyPort policy;
    private final OAuthRequestSecretPort secrets;
    private final BuildGoogleAuthorizationUrlPort authorization;
    private final ExchangeGoogleCodePort exchange;
    private final VerifyGoogleIdentityPort verifier;
    private final SignupFlowUseCase signup;
    private final ReauthenticateUserUseCase reauthenticate;
    private final Clock clock;
    private final SecureRandom random;
    @Autowired
    public GoogleAuthenticationService(OAuthAuthorizationRequestStorePort store, OAuthProtocolPolicyPort policy,
            OAuthRequestSecretPort secrets, BuildGoogleAuthorizationUrlPort authorization, ExchangeGoogleCodePort exchange,
            VerifyGoogleIdentityPort verifier, SignupFlowUseCase signup, ReauthenticateUserUseCase reauthenticate) {
        this(store, policy, secrets, authorization, exchange, verifier, signup, reauthenticate, Clock.systemUTC(), new SecureRandom());
    }
    public GoogleAuthenticationService(OAuthAuthorizationRequestStorePort store, OAuthProtocolPolicyPort policy,
            OAuthRequestSecretPort secrets, BuildGoogleAuthorizationUrlPort authorization, ExchangeGoogleCodePort exchange,
            VerifyGoogleIdentityPort verifier, SignupFlowUseCase signup, ReauthenticateUserUseCase reauthenticate,
            Clock clock, SecureRandom random) {
        this.store = store; this.policy = policy; this.secrets = secrets; this.authorization = authorization;
        this.exchange = exchange; this.verifier = verifier; this.signup = signup; this.reauthenticate = reauthenticate;
        this.clock = clock; this.random = random;
    }
    @Override public GoogleAuthorizationStart startWeb(String callerBinding, String deviceId, String targetUserId,
            String password, String requestOriginKey) {
        Started started = start(Channel.WEB, "web", callerBinding, deviceId, targetUserId, password, requestOriginKey);
        try {
            return new GoogleAuthorizationStart(authorization.authorizationUrl(started.state(), started.nonce(),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(digest(started.codeVerifier()))), started.expiresAt());
        } catch (RuntimeException failure) {
            // The request was never returned, so retire it without exposing the authorization payload.
            failWeb(started.state(), callerBinding);
            throw failure;
        }
    }
    @Override public GoogleNativeChallenge startMobile(String registration, String callerBinding, String deviceId,
            String targetUserId, String password, String requestOriginKey) {
        Started started = start(Channel.MOBILE, registration, callerBinding, deviceId, targetUserId, password, requestOriginKey);
        return new GoogleNativeChallenge(started.state(), started.nonce(), started.expiresAt());
    }
    private Started start(Channel channel, String registration, String binding, String deviceId, String target,
            String password, String origin) {
        policy.requireRegistration(channel, registration);
        requireValue(binding, 512); requireValue(deviceId, 128); requireValue(origin, 256);
        if (target != null) { requireValue(target, 36); reauthenticate.reauthenticate(target, password); }
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString(), state = randomToken(), nonce = randomToken();
        String codeVerifier = channel == Channel.WEB ? randomToken() : null;
        Instant expiresAt = now.plusSeconds(600);
        var request = new OAuthAuthorizationRequest(id, hash(state), hash(binding), secrets.encrypt(nonce, id + ":nonce"),
                codeVerifier == null ? null : secrets.encrypt(codeVerifier, id + ":verifier"), deviceId,
                target == null ? Purpose.LOGIN : Purpose.LINK, target, channel, registration, Status.PENDING, now, expiresAt);
        store.create(request, hash(origin), policy.callerHourlyLimit(), policy.originHourlyLimit());
        return new Started(state, nonce, codeVerifier, expiresAt);
    }
    @Override public GoogleAuthenticationResult completeWeb(String state, String code, String binding) {
        return complete(state, binding, Channel.WEB, request -> {
            requireValue(code, 8192);
            return exchange.exchangeCode(code, secrets.decrypt(request.encryptedCodeVerifier(), request.id() + ":verifier"),
                    secrets.decrypt(request.encryptedNonce(), request.id() + ":nonce"));
        });
    }
    @Override public GoogleAuthenticationResult completeMobile(String state, String idToken, String binding) {
        return complete(state, binding, Channel.MOBILE, request -> {
            requireValue(idToken, 32768);
            return verifier.verifyIdToken(request.registration(), idToken, secrets.decrypt(request.encryptedNonce(), request.id() + ":nonce"));
        });
    }
    private GoogleAuthenticationResult complete(String state, String binding, Channel channel,
            Function<OAuthAuthorizationRequest, GoogleIdentity> verifyIdentity) {
        var request = claim(state, binding, channel);
        try {
            policy.requireRegistration(request.channel(), request.registration());
            var identity = verifyIdentity.apply(request);
            SignupProofResult proof = signup.authenticateSocial(new SocialSignupAuthenticationCommand("GOOGLE", identity.subject(),
                    identity.email(), identity.emailVerified(), identity.emailAuthoritative(), binding,
                    request.purpose() == Purpose.LINK ? request.targetUserId() : null));
            store.finish(request.id(), Status.CONSUMED);
            return new GoogleAuthenticationResult(proof, request.deviceId());
        } catch (RuntimeException failure) {
            // PROCESSING already prevents replay even if this best-effort terminal write fails.
            try { store.finish(request.id(), Status.FAILED); } catch (RuntimeException ignored) { }
            throw failure;
        }
    }
    @Override public void failWeb(String state, String binding) {
        var request = claim(state, binding, Channel.WEB);
        store.finish(request.id(), Status.FAILED);
    }
    private OAuthAuthorizationRequest claim(String state, String binding, Channel channel) {
        policy.requireEnabled();
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw new OAuthRequestException(INVALID_REQUEST);
        requireValue(binding, 512);
        return store.claim(hash(state), hash(binding), channel, clock);
    }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new OAuthRequestException(CONFIGURATION); }
    }
    private static void requireValue(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new OAuthRequestException(INVALID_REQUEST);
    }
    private record Started(String state, String nonce, String codeVerifier, Instant expiresAt) {}
}
