package com.pikume.back.security.config;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityCorsConfigurationTest {

	@Test
	void allowedSignupOriginCanReadRetryAfterOnRateLimitedResponse() throws Exception {
		AdminSecurityProperties adminSecurityProperties = new AdminSecurityProperties(
				List.of("https://admin.pikume.com"), "sid", "csrf", "csrf-header", true, "pikume.com");
		CorsFilter corsFilter = new CorsFilter(
				new SecurityCorsConfiguration(adminSecurityProperties).corsConfigurationSource());
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SignupVerificationController())
				.addFilters(corsFilter)
				.build();

		var result = mockMvc.perform(post("/api/auth/send-verification/sign-up")
					.header(HttpHeaders.ORIGIN, "https://pikume.com")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://pikume.com"))
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
				.andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
				.contains("Authorization", "Retry-After");
	}

	@RestController
	static class SignupVerificationController {

		@PostMapping("/api/auth/send-verification/sign-up")
		public ResponseEntity<Void> sendVerificationEmail() {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header(HttpHeaders.RETRY_AFTER, "60")
					.build();
		}
	}
}
