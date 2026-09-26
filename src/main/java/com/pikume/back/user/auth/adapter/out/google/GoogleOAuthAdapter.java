package com.pikume.back.user.auth.adapter.out.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.auth.oauth2.GooglePublicKeysManager;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.pikume.back.user.auth.application.dto.GoogleIdentity;
import com.pikume.back.user.auth.application.exception.GoogleAuthenticationException;
import com.pikume.back.user.auth.application.port.out.BuildGoogleAuthorizationUrlPort;
import com.pikume.back.user.auth.application.port.out.ExchangeGoogleCodePort;
import com.pikume.back.user.auth.application.port.out.VerifyGoogleIdentityPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.pikume.back.user.auth.application.exception.GoogleAuthenticationException.Reason.*;

@Component
public class GoogleOAuthAdapter implements VerifyGoogleIdentityPort, ExchangeGoogleCodePort, BuildGoogleAuthorizationUrlPort {
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private final GoogleOAuthProperties properties;
    private final HttpTransport transport;
    private final JsonFactory jsonFactory;
    private final GooglePublicKeysManager publicKeys;
    private final GoogleIdTokenVerifier verifier;
    private final Clock clock;

    @Autowired
    public GoogleOAuthAdapter(GoogleOAuthProperties properties) {
        this(properties, new NetHttpTransport(), GsonFactory.getDefaultInstance(), Clock.systemUTC());
    }

    private GoogleOAuthAdapter(GoogleOAuthProperties properties, HttpTransport transport, JsonFactory jsonFactory, Clock clock) {
        this(properties, transport, jsonFactory, new GooglePublicKeysManager(transport, jsonFactory), clock);
    }

    GoogleOAuthAdapter(GoogleOAuthProperties properties, HttpTransport transport, JsonFactory jsonFactory,
                       GooglePublicKeysManager publicKeys, Clock clock) {
        this.properties = properties;
        this.transport = transport;
        this.jsonFactory = jsonFactory;
        this.publicKeys = publicKeys;
        this.clock = clock;
        // OIDC audience membership and presenter binding are checked below. The SDK's audience
        // predicate instead requires every token audience in one allowlist, losing registration binding.
        this.verifier = new GoogleIdTokenVerifier.Builder(publicKeys).setAudience(null)
            .setIssuers(List.of("https://accounts.google.com", "accounts.google.com"))
            .setClock(clock::millis).setAcceptableTimeSkewSeconds(60).build();
    }

    @Override
    public GoogleIdentity verifyIdToken(String clientRegistration, String idToken, String expectedNonce) {
        var registration = properties.registration(clientRegistration);
        requireText(idToken);
        requireText(expectedNonce);
        GoogleIdToken token;
        try {
            token = GoogleIdToken.parse(jsonFactory, idToken);
            validateClaims(token, registration, expectedNonce);
        } catch (IOException | IllegalArgumentException | ClassCastException exception) {
            throw new GoogleAuthenticationException(INVALID_IDENTITY);
        }
        // Keep certificate fetch/parse failures separate from malformed token/signature failures.
        // A singleton manager is shared by all registrations and honors Google's cache headers.
        try {
            if (publicKeys.getPublicKeys().isEmpty()) throw new GoogleAuthenticationException(UNAVAILABLE);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new GoogleAuthenticationException(UNAVAILABLE);
        }
        try {
            if (!verifier.verify(token)) throw new GoogleAuthenticationException(INVALID_IDENTITY);
        } catch (IOException exception) {
            throw new GoogleAuthenticationException(UNAVAILABLE);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new GoogleAuthenticationException(INVALID_IDENTITY);
        }
        var payload = token.getPayload();
        String email = payload.getEmail();
        if (email != null && email.isBlank()) email = null;
        boolean emailVerified = Boolean.TRUE.equals(payload.getEmailVerified());
        boolean authoritative = email != null && emailVerified &&
            (email.toLowerCase(Locale.ROOT).endsWith("@gmail.com") ||
                (payload.getHostedDomain() != null && !payload.getHostedDomain().isBlank()));
        return new GoogleIdentity(payload.getSubject(), email, emailVerified, authoritative);
    }

    private void validateClaims(GoogleIdToken token, GoogleOAuthProperties.MobileRegistration registration, String nonce) {
        var payload = token.getPayload();
        List<String> audiences = payload.getAudienceAsList();
        String presenter = payload.getAuthorizedParty();
        String expectedPresenter = registration.authorizedParty() == null ? registration.audience() : registration.authorizedParty();
        long now = clock.instant().getEpochSecond();
        if (!"RS256".equals(token.getHeader().getAlgorithm()) ||
            (!"https://accounts.google.com".equals(payload.getIssuer()) && !"accounts.google.com".equals(payload.getIssuer())) ||
            audiences.isEmpty() || !audiences.contains(registration.audience()) ||
            (presenter != null && !expectedPresenter.equals(presenter)) ||
            ((audiences.size() > 1 || registration.authorizedParty() != null) && presenter == null) ||
            payload.getExpirationTimeSeconds() == null || payload.getExpirationTimeSeconds() <= now ||
            payload.getIssuedAtTimeSeconds() == null || payload.getIssuedAtTimeSeconds() > now + 60 ||
            payload.getIssuedAtTimeSeconds() >= payload.getExpirationTimeSeconds() ||
            payload.getSubject() == null || payload.getSubject().isBlank() || payload.getSubject().length() > 255 ||
            !nonce.equals(payload.get("nonce"))) {
            throw new GoogleAuthenticationException(INVALID_IDENTITY);
        }
    }

    @Override
    public GoogleIdentity exchangeCode(String code, String codeVerifier, String expectedNonce) {
        properties.requireEnabled();
        requireText(code);
        requireText(expectedNonce);
        if (codeVerifier == null || !codeVerifier.matches("[A-Za-z0-9._~-]{43,128}")) {
            throw new GoogleAuthenticationException(INVALID_IDENTITY);
        }
        String idToken;
        try {
            var request = transport.createRequestFactory().buildPostRequest(new GenericUrl(TOKEN_ENDPOINT),
                new UrlEncodedContent(Map.of("code", code, "code_verifier", codeVerifier,
                    "client_id", properties.getWebClientId(), "client_secret", properties.getWebClientSecret(),
                    "redirect_uri", properties.getWebRedirectUri(), "grant_type", "authorization_code")));
            request.setLoggingEnabled(false).setCurlLoggingEnabled(false).setFollowRedirects(false)
                .setNumberOfRetries(0).setConnectTimeout(5000).setReadTimeout(10000).setThrowExceptionOnExecuteError(false);
            HttpResponse response = request.execute();
            try {
                if (response.getStatusCode() == 429 || response.getStatusCode() >= 500) {
                    throw new GoogleAuthenticationException(UNAVAILABLE);
                }
                GenericJson body = jsonFactory.fromInputStream(response.getContent(), GenericJson.class);
                if (!response.isSuccessStatusCode()) {
                    Object error = body.get("error");
                    if ("invalid_client".equals(error) || "unauthorized_client".equals(error) || "redirect_uri_mismatch".equals(error)) {
                        throw new GoogleAuthenticationException(CONFIGURATION);
                    }
                    throw new GoogleAuthenticationException("invalid_grant".equals(error) ? INVALID_IDENTITY : UNAVAILABLE);
                }
                if (!(body.get("id_token") instanceof String value) || value.isBlank()) {
                    throw new GoogleAuthenticationException(UNAVAILABLE);
                }
                idToken = value;
            } finally {
                response.disconnect();
            }
        } catch (IOException | IllegalArgumentException exception) {
            // Never propagate remote exception bodies, request credentials, or provider tokens.
            throw new GoogleAuthenticationException(UNAVAILABLE);
        }
        return verifyIdToken("web", idToken, expectedNonce);
    }

    @Override
    public String authorizationUrl(String state, String nonce, String codeChallenge) {
        properties.requireEnabled();
        requireText(state);
        requireText(nonce);
        if (codeChallenge == null || !codeChallenge.matches("[A-Za-z0-9_-]{43}")) {
            throw new GoogleAuthenticationException(INVALID_IDENTITY);
        }
        var url = new GenericUrl(AUTHORIZATION_ENDPOINT);
        url.putAll(Map.of("client_id", properties.getWebClientId(), "redirect_uri", properties.getWebRedirectUri(),
            "response_type", "code", "scope", "openid email", "state", state, "nonce", nonce,
            "code_challenge", codeChallenge, "code_challenge_method", "S256"));
        return url.build();
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new GoogleAuthenticationException(INVALID_IDENTITY);
    }
}
