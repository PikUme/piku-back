package com.pikume.back.user.auth.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SignupWebCredentialsTest {
    private final SignupWebCredentials credentials = new SignupWebCredentials(request -> {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("https://www.pikume.com"));
        return config;
    });
    @Test void bootstrapDoesNotExposeBindingAndSetsHostOnlySecureCookies() {
        var request = new MockHttpServletRequest("GET","/api/auth/signup/progress");
        var response = new MockHttpServletResponse();
        var context = credentials.bootstrap(request,response);
        assertThat(context.binding()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(context.csrf()).isNotEqualTo(context.binding());
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allSatisfy(cookie -> {
            assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/");
            assertThat(cookie).doesNotContain("Domain=");
        });
    }
    @Test void rejectsMissingCsrfBeforeApplicationMutation() {
        var request = new MockHttpServletRequest("POST","/api/auth/signup/agreements");
        request.addHeader("Origin","https://www.pikume.com");
        assertThatThrownBy(() -> credentials.requireMutation(request)).isInstanceOf(SignupWebException.class);
    }
    @Test void rejectsCrossOriginEvenWithMatchingCsrf() {
        var request = new MockHttpServletRequest("POST","/api/auth/signup/agreements");
        request.addHeader("Origin","https://evil.example");
        request.addHeader("X-Signup-CSRF","csrf");
        request.setCookies(new jakarta.servlet.http.Cookie(SignupWebCredentials.CSRF,"csrf"));
        assertThatThrownBy(() -> credentials.requireMutation(request)).isInstanceOf(SignupWebException.class);
    }
    @Test void acceptsSameOriginBoundCsrf() {
        var request = new MockHttpServletRequest("POST","/api/auth/signup/agreements");
        request.addHeader("Origin","https://www.pikume.com");
        request.addHeader("X-Signup-CSRF","a".repeat(43));
        request.setCookies(new jakarta.servlet.http.Cookie(SignupWebCredentials.CSRF,"a".repeat(43)),new jakarta.servlet.http.Cookie(SignupWebCredentials.BINDING,"b".repeat(43)));
        assertThat(credentials.requireMutation(request)).isEqualTo("b".repeat(43));
    }
}
