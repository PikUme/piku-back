package com.pikume.back.security.config;

import com.pikume.back.security.adapter.in.web.BearerTokenAuthenticationFilter;
import com.pikume.back.security.adapter.in.web.CompletedProfileAuthorizationManager;
import com.pikume.back.security.adapter.in.web.ProblemDetailAccessDeniedHandler;
import com.pikume.back.security.adapter.in.web.ProblemDetailAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class UserSecurityConfiguration {

	private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
	private final Environment environment;
	private final CorsConfigurationSource corsConfigurationSource;
	private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
	private final ProblemDetailAccessDeniedHandler accessDeniedHandler;
	private final CompletedProfileAuthorizationManager completedProfileAuthorizationManager;

	@Value("${monitoring.allowed-ips:}")
	private String allowedIps;

	@Bean
	@Order(2)
	public SecurityFilterChain userSecurityFilterChain(HttpSecurity http) throws Exception {
		List<String> permittedPaths = new ArrayList<>(Arrays.asList(
				"/api/auth/login",
				"/api/auth/reissue",
				"/api/auth/signup",
				"/api/auth/send-verification/sign-up",
				"/api/auth/send-verification/password-reset",
				"/api/auth/verify-code",
				"/api/auth/password-reset",
				"/api/auth/email",
				"/api/auth/email-domains",
				"/api/mobile/auth/login",
				"/api/mobile/auth/reissue",
				"/api/mobile/auth/logout",
				"/api/characters/fixed",
				"/api/search"));
		for (String base : List.of("/api/auth", "/api/mobile/auth")) {
			for (String action : List.of("progress", "agreements", "email/code", "email", "social/email")) {
				permittedPaths.add(base + "/signup/" + action);
			}
		}
		permittedPaths.addAll(List.of("/api/auth/oauth/google/start", "/api/auth/oauth/google/callback",
				"/api/mobile/auth/oauth/google/challenge", "/api/mobile/auth/oauth/google/complete"));

		if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
			permittedPaths.addAll(Arrays.asList(
					"/v3/api-docs/**",
					"/swagger-ui/**",
					"/swagger-resources/**",
					"/swagger-ui.html"));
		}

		http
				.securityContext(context -> context.requireExplicitSave(false))
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.exceptionHandling(exceptionHandling -> exceptionHandling
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/**").access((authentication, context) ->
								new AuthorizationDecision(isMonitoringRequestAllowed(context.getRequest())))
						.requestMatchers(permittedPaths.toArray(new String[0]))
						.permitAll()
						.requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/signup/nickname", "/api/auth/signup/profile",
								"/api/mobile/auth/me", "/api/mobile/auth/signup/nickname", "/api/mobile/auth/signup/profile").authenticated()
						.requestMatchers("/api/diary/ai/**").access(completedProfileAuthorizationManager)
						.requestMatchers(HttpMethod.GET,
								"/api/diary",
								"/api/diary/**",
								"/api/comments",
								"/api/comments/*/replies",
								"/api/users/{userId}/profile-preview")
						.permitAll()
						.anyRequest().access(completedProfileAuthorizationManager))
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		http.addFilterBefore(
				bearerTokenAuthenticationFilter,
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	private boolean isMonitoringRequestAllowed(jakarta.servlet.http.HttpServletRequest request) {
		String remoteAddress = request.getRemoteAddr();
		if ("127.0.0.1".equals(remoteAddress)
				|| "0:0:0:0:0:0:0:1".equals(remoteAddress)
				|| "localhost".equals(remoteAddress)) {
			return true;
		}

		if (allowedIps == null || allowedIps.isEmpty()) {
			return false;
		}

		return Arrays.stream(allowedIps.split(","))
				.map(String::trim)
				.map(IpAddressMatcher::new)
				.anyMatch(matcher -> matcher.matches(request));
	}
}
