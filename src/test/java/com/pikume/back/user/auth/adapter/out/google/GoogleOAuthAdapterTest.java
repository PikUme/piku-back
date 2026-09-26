package com.pikume.back.user.auth.adapter.out.google;

import com.google.api.client.googleapis.auth.oauth2.GooglePublicKeysManager;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.pikume.back.user.auth.application.exception.GoogleAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.pikume.back.user.auth.application.exception.GoogleAuthenticationException.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleOAuthAdapterTest {
    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();
    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String VERIFIER = "a".repeat(43);
    private KeyPair keys;
    private GoogleOAuthProperties properties;
    private GooglePublicKeysManager publicKeys;
    private MockLowLevelHttpResponse response;
    private MockLowLevelHttpRequest request;
    private GoogleOAuthAdapter adapter;
    private String requestMethod;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        properties = configured();
        publicKeys = mock(GooglePublicKeysManager.class);
        when(publicKeys.getJsonFactory()).thenReturn(JSON);
        when(publicKeys.getPublicKeys()).thenReturn(List.of(keys.getPublic()));
        response = new MockLowLevelHttpResponse().setContentType("application/json");
        request = new MockLowLevelHttpRequest().setResponse(response);
        var transport = new MockHttpTransport() {
            @Override
            public MockLowLevelHttpRequest buildRequest(String method, String url) {
                requestMethod = method;
                request.setUrl(url);
                return request;
            }
        };
        when(publicKeys.getTransport()).thenReturn(transport);
        adapter = new GoogleOAuthAdapter(properties, transport, JSON, publicKeys, CLOCK);
    }

    @Test
    void returnsVerifiedGmailIdentityFromTheSelectedRegistration() throws Exception {
        var identity = adapter.verifyIdToken("android", token(payload()), "nonce");
        assertThat(identity.subject()).isEqualTo("google-subject");
        assertThat(identity.email()).isEqualTo("person@gmail.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.emailAuthoritative()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"person@gmail.com,false,,false", "person@external.test,true,,false", "person@workspace.test,true,workspace.test,true", ",false,,false"})
    void returnsEmailTrustFactsWithoutRejectingAuthenticatedIdentity(String email, boolean verified, String hd, boolean authoritative) throws Exception {
        var payload = payload().set("email", email).set("email_verified", verified).set("hd", hd);
        var identity = adapter.verifyIdToken("android", token(payload), "nonce");
        assertThat(identity.subject()).isEqualTo("google-subject");
        assertThat(identity.email()).isEqualTo(email);
        assertThat(identity.emailVerified()).isEqualTo(verified);
        assertThat(identity.emailAuthoritative()).isEqualTo(authoritative);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aud", "iss", "exp", "iat", "nonce", "sub", "azp", "missing-exp", "missing-iat", "missing-aud", "missing-iss", "multiple-aud", "signature"})
    void rejectsInvalidTokenBoundaryClaims(String invalid) throws Exception {
        var payload = payload();
        switch (invalid) {
            case "aud" -> payload.setAudience("web-client");
            case "iss" -> payload.setIssuer("https://attacker.test");
            case "exp" -> payload.setExpirationTimeSeconds(NOW - 1);
            case "iat" -> payload.setIssuedAtTimeSeconds(NOW + 61);
            case "nonce" -> payload.set("nonce", "another-request");
            case "sub" -> payload.setSubject(" ");
            case "azp" -> payload.set("azp", "other-client");
            case "missing-exp" -> payload.setExpirationTimeSeconds(null);
            case "missing-iat" -> payload.setIssuedAtTimeSeconds(null);
            case "missing-aud" -> payload.setAudience(null);
            case "missing-iss" -> payload.setIssuer(null);
            case "multiple-aud" -> payload.setAudience(List.of("android-client", "other-client"));
            case "signature" -> keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        }
        var token = token(payload);
        assertFailure(() -> adapter.verifyIdToken("android", token, "nonce"), INVALID_IDENTITY);
    }

    @Test
    void mobileWebAudienceRequiresTheConfiguredNativePresenter() throws Exception {
        var token = token(payload().setAudience("web-client").set("azp", "android-client"));
        assertThat(adapter.verifyIdToken("android-web", token, "nonce").subject()).isEqualTo("google-subject");
        var missingPresenter = token(payload().setAudience("web-client"));
        assertFailure(() -> adapter.verifyIdToken("android-web", missingPresenter, "nonce"), INVALID_IDENTITY);
        var wrongPresenter = token(payload().setAudience("web-client").set("azp", "other-client"));
        assertFailure(() -> adapter.verifyIdToken("android-web", wrongPresenter, "nonce"), INVALID_IDENTITY);
    }

    @Test
    void acceptsMultipleAudiencesOnlyWithMatchingAuthorizedParty() throws Exception {
        var payload = payload().setAudience(List.of("android-client", "other-client")).set("azp", "android-client");
        assertThat(adapter.verifyIdToken("android", token(payload), "nonce").subject()).isEqualTo("google-subject");
    }

    @Test
    void rejectsMalformedTokenAndUnknownRegistration() {
        assertFailure(() -> adapter.verifyIdToken("android", "not-a-jwt", "nonce"), INVALID_IDENTITY);
        assertFailure(() -> adapter.verifyIdToken("android-client", "not-a-jwt", "nonce"), INVALID_IDENTITY);
    }

    @Test
    void distinguishesCertificateOutageFromInvalidIdentity() throws Exception {
        when(publicKeys.getPublicKeys()).thenThrow(new IOException("remote failure"));
        var token = token(payload());
        assertFailure(() -> adapter.verifyIdToken("android", token, "nonce"), UNAVAILABLE);
    }

    @Test
    void exchangesCodeWithConfiguredWebBindingAndVerifiesReturnedToken() throws Exception {
        response.setContent(JSON.toString(Map.of("id_token", token(payload().setAudience("web-client")))));
        assertThat(adapter.exchangeCode("code + value", VERIFIER, "nonce").subject()).isEqualTo("google-subject");
        var form = new GenericUrl("https://unused.test/?" + request.getContentAsString());
        assertThat(form.get("code")).isEqualTo(List.of("code + value"));
        assertThat(form.get("code_verifier")).isEqualTo(List.of(VERIFIER));
        assertThat(form.get("client_id")).isEqualTo(List.of("web-client"));
        assertThat(form.get("client_secret")).isEqualTo(List.of("test-secret"));
        assertThat(form.get("redirect_uri")).isEqualTo(List.of("https://api.example.test/oauth/callback"));
        assertThat(form.get("grant_type")).isEqualTo(List.of("authorization_code"));
        assertThat(request.getUrl()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(requestMethod).isEqualTo("POST");
    }

    @ParameterizedTest
    @CsvSource({"400,invalid_grant,INVALID_IDENTITY", "400,invalid_client,CONFIGURATION", "503,server_error,UNAVAILABLE", "429,rate_limit,UNAVAILABLE"})
    void classifiesTokenEndpointErrors(int status, String error, GoogleAuthenticationException.Reason reason) {
        response.setStatusCode(status).setContent("{\"error\":\"" + error + "\",\"error_description\":\"sensitive\"}");
        assertFailure(() -> adapter.exchangeCode("code", VERIFIER, "nonce"), reason);
    }

    @Test
    void authorizationUrlBindsNonceStateAndPkceToConfiguredWebClient() {
        var url = new GenericUrl(adapter.authorizationUrl("state", "nonce", "b".repeat(43)));
        assertThat(url.getHost()).isEqualTo("accounts.google.com");
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.get("response_type")).isEqualTo(List.of("code"));
        assertThat(url.get("scope")).isEqualTo(List.of("openid email"));
        assertThat(url.get("state")).isEqualTo(List.of("state"));
        assertThat(url.get("nonce")).isEqualTo(List.of("nonce"));
        assertThat(url.get("code_challenge")).isEqualTo(List.of("b".repeat(43)));
        assertThat(url.get("code_challenge_method")).isEqualTo(List.of("S256"));
        assertThat(url.get("redirect_uri")).isEqualTo(List.of("https://api.example.test/oauth/callback"));
        assertThat(url.get("client_id")).isEqualTo(List.of("web-client"));
    }

    @Test
    void disabledProviderFailsClosed() {
        properties.setEnabled(false);
        assertFailure(() -> adapter.verifyIdToken("android", "token", "nonce"), DISABLED);
        assertFailure(() -> adapter.exchangeCode("code", VERIFIER, "nonce"), DISABLED);
        assertFailure(() -> adapter.authorizationUrl("state", "nonce", "b".repeat(43)), DISABLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.example.test/callback", "https://user@api.example.test/callback", "https://api.example.test/callback#fragment", "invalid"})
    void rejectsUnsafeConfiguredRedirect(String redirect) {
        properties.setWebRedirectUri(redirect);
        assertFailure(properties::validate, CONFIGURATION);
    }

    @Test
    void rejectsMissingNonceAndMalformedPkceBeforeRemoteCalls() {
        assertFailure(() -> adapter.verifyIdToken("android", "token", ""), INVALID_IDENTITY);
        assertFailure(() -> adapter.exchangeCode("code", "short", "nonce"), INVALID_IDENTITY);
        assertFailure(() -> adapter.authorizationUrl("state", "nonce", "short"), INVALID_IDENTITY);
    }

    @Test
    void codeExchangeAppliesNonceValidationAndRejectsMissingIdentityToken() throws Exception {
        response.setContent(JSON.toString(Map.of("id_token", token(payload().setAudience("web-client").set("nonce", "other")))));
        assertFailure(() -> adapter.exchangeCode("code", VERIFIER, "nonce"), INVALID_IDENTITY);
        response.setContent("{}");
        assertFailure(() -> adapter.exchangeCode("code", VERIFIER, "nonce"), UNAVAILABLE);
    }

    @Test
    void enabledConfigurationRejectsMissingSecretAndReservedMobileRegistration() {
        properties.setWebClientSecret(" ");
        assertFailure(properties::validate, CONFIGURATION);
        properties = configured();
        properties.setMobileRegistrations(Map.of("web", new GoogleOAuthProperties.MobileRegistration("spoofed", null)));
        assertFailure(properties::validate, CONFIGURATION);
        new GoogleOAuthProperties().validate();
    }

    private static GoogleOAuthProperties configured() {
        var properties = new GoogleOAuthProperties();
        properties.setEnabled(true);
        properties.setWebClientId("web-client");
        properties.setWebClientSecret("test-secret");
        properties.setWebRedirectUri("https://api.example.test/oauth/callback");
        properties.setMobileRegistrations(Map.of("android", new GoogleOAuthProperties.MobileRegistration("android-client", null),
            "android-web", new GoogleOAuthProperties.MobileRegistration("web-client", "android-client")));
        return properties;
    }

    private JsonWebToken.Payload payload() {
        return new JsonWebToken.Payload().setIssuer("https://accounts.google.com").setAudience("android-client")
            .setSubject("google-subject").setIssuedAtTimeSeconds(NOW - 10).setExpirationTimeSeconds(NOW + 300)
            .set("nonce", "nonce").set("email", "person@gmail.com").set("email_verified", true);
    }

    private String token(JsonWebToken.Payload payload) throws Exception {
        return JsonWebSignature.signUsingRsaSha256((RSAPrivateKey) keys.getPrivate(), JSON,
            new JsonWebSignature.Header().setAlgorithm("RS256").setKeyId("test-key"), payload);
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, GoogleAuthenticationException.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(GoogleAuthenticationException.class,
            exception -> {
                assertThat(exception.getReason()).isEqualTo(reason);
                assertThat(exception.getMessage()).doesNotContain("sensitive", "test-secret");
                assertThat(exception.getCause()).isNull();
            });
    }
}
