package com.pikume.back.user.auth.adapter.in.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfigurationSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import static com.pikume.back.user.auth.adapter.in.web.SignupWebException.Reason.*;

@Component
public class SignupWebCredentials {
    public static final String BINDING = "__Host-pk-signup-binding";
    public static final String CSRF = "__Host-pk-signup-csrf";
    public static final String PROOF = com.pikume.back.security.adapter.in.web.AuthWebConstants.SIGNUP_PROOF_COOKIE;
    public static final String BINDING_HEADER = "X-Signup-Binding";
    public static final String PROOF_HEADER = "X-Signup-Proof";
    public static final String CSRF_HEADER = "X-Signup-CSRF";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CorsConfigurationSource origins;
    public SignupWebCredentials(@Qualifier("corsConfigurationSource") CorsConfigurationSource origins) {
        this.origins = origins;
    }
    public record Context(String binding, String csrf) {}

    public Context bootstrap(HttpServletRequest request, HttpServletResponse response) {
        if (request.getHeader(HttpHeaders.ORIGIN) != null) requireOrigin(request);
        String binding = cookie(request, BINDING);
        String csrf = cookie(request, CSRF);
        if (!validOpaque(binding)) { binding = randomToken(); setCookie(response, BINDING, binding, 86400); }
        if (!validOpaque(csrf)) { csrf = randomToken(); setCookie(response, CSRF, csrf, 86400); }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return new Context(binding, csrf);
    }

    public String requireMutation(HttpServletRequest request) {
        requireOrigin(request);
        String expected = cookie(request, CSRF);
        String actual = request.getHeader(CSRF_HEADER);
        if (!validOpaque(expected) || !validOpaque(actual) ||
            !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new SignupWebException(CSRF_INVALID);
        }
        return requireBinding(request);
    }

    public void requireOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        var configuration = origins.getCorsConfiguration(request);
        if (origin == null || configuration == null || configuration.checkOrigin(origin) == null) {
            throw new SignupWebException(ORIGIN_FORBIDDEN);
        }
    }

    public String requireBinding(HttpServletRequest request) {
        String value = mobile(request) ? request.getHeader(BINDING_HEADER) : cookie(request, BINDING);
        if (!validOpaque(value)) throw new SignupWebException(CALLER_REQUIRED);
        return value;
    }

    public String proof(HttpServletRequest request) {
        String value = mobile(request) ? request.getHeader(PROOF_HEADER) : cookie(request, PROOF);
        return validOpaque(value) ? value : null;
    }

    public String requireProof(HttpServletRequest request) {
        String proof = proof(request);
        if (proof == null) throw new SignupWebException(PROOF_REQUIRED);
        return proof;
    }

    public String requireDevice(HttpServletRequest request) {
        String device = request.getHeader("Device-Id");
        if (device == null || device.isBlank() || device.length() > 128) throw new SignupWebException(DEVICE_REQUIRED);
        return device;
    }

    public String mobileBootstrap(HttpServletRequest request) {
        String current = request.getHeader(BINDING_HEADER);
        return validOpaque(current) ? current : randomToken();
    }

    public boolean hasProofCookie(HttpServletRequest request) { return cookie(request, PROOF) != null; }
    public void storeProof(HttpServletResponse response, String proof, Instant expiresAt) {
        long remainingSeconds = Math.max(0, Duration.between(Instant.now(), expiresAt).getSeconds());
        if (remainingSeconds == 0) clearProof(response);
        else setCookie(response, PROOF, proof, remainingSeconds);
    }
    public void clearProof(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, com.pikume.back.security.adapter.in.web.SignupProofCookie.expired().toString());
    }
    public static boolean mobile(HttpServletRequest request) { return request.getRequestURI().startsWith("/api/mobile/"); }
    public static boolean validOpaque(String value) { return value != null && value.matches("[A-Za-z0-9_-]{32,128}"); }
    public static String randomToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    private void setCookie(HttpServletResponse response, String name, String value, long age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name,value).httpOnly(true).secure(true)
            .path("/").sameSite("Lax").maxAge(age).build().toString());
    }
}
