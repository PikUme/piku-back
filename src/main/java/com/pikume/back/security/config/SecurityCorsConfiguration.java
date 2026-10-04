package com.pikume.back.security.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityCorsConfiguration {

	private final AdminSecurityProperties adminSecurityProperties;

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration adminConfiguration = new CorsConfiguration();
		adminConfiguration.setAllowedOrigins(adminSecurityProperties.allowedOrigins());
		adminConfiguration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		adminConfiguration.setAllowedHeaders(
				List.of("Content-Type", "Accept", adminSecurityProperties.csrfHeaderName()));
		adminConfiguration.setAllowCredentials(true);

		CorsConfiguration userConfiguration = new CorsConfiguration();
		userConfiguration.setAllowedOrigins(List.of(
				"http://localhost:3000",
				"http://localhost:3001",
				"https://pikume.com",
				"https://www.pikume.com"));
		userConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		userConfiguration.setAllowedHeaders(List.of("*"));
		userConfiguration.setExposedHeaders(List.of("Authorization", "Retry-After"));
		userConfiguration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/admin/**", adminConfiguration);
		source.registerCorsConfiguration("/**", userConfiguration);
		return source;
	}
}
