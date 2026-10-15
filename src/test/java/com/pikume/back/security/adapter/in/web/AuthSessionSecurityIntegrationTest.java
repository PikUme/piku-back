package com.pikume.back.security.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.admin.adapter.out.persistence.AdminAccountJpaRepository;
import com.pikume.back.admin.adapter.out.persistence.AdminSessionJpaRepository;
import com.pikume.back.admin.domain.AdminAccount;
import com.pikume.back.admin.domain.AdminSession;
import com.pikume.back.admin.domain.AdminRole;
import com.pikume.back.admin.application.port.out.AdminSessionCredentialPort;
import com.pikume.back.admin.application.port.out.AdminSessionCachePort;
import com.pikume.back.security.adapter.in.web.problem.SecurityProblemType;
import com.pikume.back.security.adapter.out.token.JwtTokenProvider;
import com.pikume.back.testsupport.FixedCharacterCatalogIsolationConfiguration;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.dto.UserIdentityView;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedCharacterCatalogIsolationConfiguration.class)
@DisplayName("Auth session security")
class AuthSessionSecurityIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtProvider;

	@Autowired
	private AdminSessionJpaRepository adminSessionJpaRepository;

	@Autowired
	private AdminAccountJpaRepository adminAccountJpaRepository;

	@Autowired
	private AdminSessionCredentialPort adminSessionCredentialPort;

	@MockitoBean
	private AdminSessionCachePort adminSessionCachePort;

	@MockitoBean
	private QueryUserIdentityUseCase queryUserIdentityUseCase;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CorsConfigurationSource corsConfigurationSource;

	@Test
	@DisplayName("GET /api/auth/me는 토큰이 없으면 Problem Details 401을 반환한다")
	void getCurrentUserRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/auth/me")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value(SecurityProblemType.UNAUTHENTICATED.type().toString()))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.instance").value("/api/auth/me"));
	}

	@Test
	@DisplayName("회원가입 전 닉네임 예약은 인증 없이 공개 API 입력 검증까지 도달한다")
	void signupNicknameReservationIsPublic() throws Exception {
		mockMvc.perform(post("/api/auth/signup/nickname-reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"","emailVerificationToken":"","nickname":""}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	@DisplayName("GET /api/auth/me는 유효한 Bearer 토큰의 사용자 정보를 반환한다")
	void getCurrentUserReturnsUserForBearerTokenSubjectUserId() throws Exception {
		String userId = "session-user-id";
		UserIdentityView user = new UserIdentityView(
				userId,
				"encoded-password",
				"session-user",
				new UserAvatarReference(
						"public/characters/fixed/base_image_1.webp",
						false,
						true));
		given(queryUserIdentityUseCase.queryUserIdentityById(userId)).willReturn(Optional.of(user));
		String accessToken = jwtProvider.generateAccessToken(userId);

		mockMvc.perform(get("/api/auth/me")
						.header(HttpHeaders.AUTHORIZATION, AuthWebConstants.BEARER_PREFIX + accessToken)
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("토큰 검증 성공"))
				.andExpect(jsonPath("$.user.id").value(userId))
				.andExpect(jsonPath("$.user.email").doesNotExist())
				.andExpect(jsonPath("$.user.nickname").value(user.nickname()))
				.andExpect(jsonPath("$.user.avatarUrl")
						.value("http://localhost:9000/piku/public/characters/fixed/base_image_1.webp"));
	}

	@Test
	@DisplayName("POST /api/auth/login은 인증 없이도 로그인 컨트롤러까지 도달한다")
	void loginRemainsPublicAfterAuthMatcherTightening() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"email", "missing@example.com",
								"password", "wrong-password")))
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value(SecurityProblemType.INVALID_CREDENTIALS.type().toString()))
				.andExpect(jsonPath("$.detail").value("이메일 또는 비밀번호가 올바르지 않습니다."));
	}

	@Test
	@DisplayName("사용자 토큰으로 관리자 API에 접근할 수 없다")
	void userTokenCannotAccessAdminApi() throws Exception {
		String userId = "admin-boundary-user-id";
		UserIdentityView user = new UserIdentityView(
				userId,
				"encoded-password",
				"boundary-user",
				new UserAvatarReference(
						"public/characters/fixed/boundary-user.webp",
						false,
						true));
		given(queryUserIdentityUseCase.queryUserIdentityById(userId)).willReturn(Optional.of(user));
		String accessToken = jwtProvider.generateAccessToken(userId);

		mockMvc.perform(get("/api/admin/statistics/dashboard")
						.header(HttpHeaders.AUTHORIZATION, AuthWebConstants.BEARER_PREFIX + accessToken)
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
						.string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(jsonPath("$.type").value(SecurityProblemType.UNAUTHENTICATED.type().toString()))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.instance").value("/api/admin/statistics/dashboard"));
	}

	@Test
	@DisplayName("관리자 세션 쿠키로 일반 사용자 API에 접근할 수 없다")
	void adminSessionCannotAccessUserApi() throws Exception {
		mockMvc.perform(get("/api/auth/me")
						.cookie(new Cookie("pk-a91f", "admin-session"))
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value(SecurityProblemType.UNAUTHENTICATED.type().toString()))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.instance").value("/api/auth/me"));
	}

	@Test
	@DisplayName("관리자 세션 쿠키는 관리자 보호 경로의 보안 검사를 통과한다")
	void adminSessionCookiePassesAdminSecurityBoundary() throws Exception {
		LocalDateTime now = LocalDateTime.now();
		AdminAccount admin = AdminAccount.invite(
				"boundary-admin@example.com",
				"BoundaryAdmin",
				AdminRole.SUPER_ADMIN,
				"temporary-password-hash",
				now,
				now.plusHours(24));
		admin.completeCredentialSetup("boundary-admin", "password-hash");
		admin.startOtpRegistration("protected-otp-secret");
		admin.completeOtpRegistration();
		adminAccountJpaRepository.saveAndFlush(admin);

		String rawSessionToken = "raw-admin-session-token";
		AdminSession session = AdminSession.startAnonymous(
				adminSessionCredentialPort.hash(rawSessionToken),
				adminSessionCredentialPort.hash("raw-csrf-token"),
				now,
				now.plusMinutes(5));
		session.bindAdmin(admin.getId(), admin.getAuthenticationVersion(),
				com.pikume.back.admin.domain.AdminSessionPhase.LOGIN_VERIFY_OTP, now.plusMinutes(5), now);
		session.authenticate(
				adminSessionCredentialPort.hash(rawSessionToken),
				adminSessionCredentialPort.hash("raw-csrf-token"),
				admin.getAuthenticationVersion(), now.plusHours(8), now.plusMinutes(30), now);
		adminSessionJpaRepository.saveAndFlush(session);

		mockMvc.perform(get("/api/admin/statistics/dashboard")
						.cookie(new Cookie("pk-a91f", rawSessionToken))
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currentMemberCount").exists())
				.andExpect(jsonPath("$.dailyStatistics").isArray());
	}

	@Test
	@DisplayName("관리자 API CORS는 관리자 Origin만 허용한다")
	void adminCorsAllowsOnlyAdminOrigin() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/statistics/dashboard");

		CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(request);

		assertThat(configuration).isNotNull();
		assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:3000");
		assertThat(configuration.getAllowedOrigins()).doesNotContain("https://www.pikume.com");
		assertThat(configuration.getAllowedHeaders()).doesNotContain("*");
	}

	@Test
	@DisplayName("일반 API CORS는 기존 Web Origin과 Authorization 노출 계약을 유지한다")
	void userCorsKeepsExistingWebOriginsAndAuthorizationHeader() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/diary");

		CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(request);

		assertThat(configuration).isNotNull();
		assertThat(configuration.getAllowedOrigins()).containsExactly(
				"http://localhost:3000",
				"http://localhost:3001",
				"https://pikume.com",
				"https://www.pikume.com");
		assertThat(configuration.getAllowedMethods())
				.containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
		assertThat(configuration.getAllowedHeaders()).containsExactly("*");
		assertThat(configuration.getExposedHeaders()).containsExactly(
				HttpHeaders.AUTHORIZATION,
				HttpHeaders.RETRY_AFTER);
		assertThat(configuration.getAllowCredentials()).isTrue();
	}

	@Test
	@DisplayName("CSRF 초기화는 비식별 세션 쿠키와 CSRF 쿠키를 발급한다")
	void initializesCsrfCookies() throws Exception {
		var result = mockMvc.perform(post("/api/admin/auth/csrf")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000"))
				.andExpect(status().isNoContent())
				.andReturn();

		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.anyMatch(value -> value.startsWith("pk-a91f=") && value.contains("HttpOnly") && value.contains("SameSite=Strict"))
				.anyMatch(value -> value.startsWith("pk-b74d=") && !value.contains("HttpOnly") && value.contains("SameSite=Strict"));
	}

	@Test
	@DisplayName("로그인은 세션 쿠키, CSRF 쿠키와 헤더, Origin이 모두 일치할 때 컨트롤러에 도달한다")
	void loginRequiresSessionBoundCsrf() throws Exception {
		storeAnonymousSession("raw-session", "raw-csrf");

		mockMvc.perform(post("/api/admin/auth/login")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.header("X-PK-C83F", "raw-csrf")
						.cookie(new Cookie("pk-a91f", "raw-session"), new Cookie("pk-b74d", "raw-csrf"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"loginId", "missing-admin",
								"password", "WrongPass1!"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/admin/invalid-credentials"));
	}

	@Test
	@DisplayName("CSRF 헤더가 누락되거나 쿠키와 다르면 Problem Details 403을 반환한다")
	void rejectsMissingOrMismatchedCsrf() throws Exception {
		storeAnonymousSession("raw-session", "raw-csrf");

		mockMvc.perform(post("/api/admin/auth/login")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.cookie(new Cookie("pk-a91f", "raw-session"), new Cookie("pk-b74d", "raw-csrf"))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/admin/csrf-invalid"))
				.andExpect(jsonPath("$.status").value(403));

		mockMvc.perform(post("/api/admin/auth/login")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.header("X-PK-C83F", "other-csrf")
						.cookie(new Cookie("pk-a91f", "raw-session"), new Cookie("pk-b74d", "raw-csrf"))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	@DisplayName("관리자 CORS preflight는 허용 Origin과 비식별 CSRF 헤더를 노출한다")
	void adminPreflightUsesSharedOriginConfiguration() throws Exception {
		mockMvc.perform(options("/api/admin/auth/login")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,X-PK-C83F"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
						.string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
	}

	@Test
	@DisplayName("1차 범위 밖 관리자 공개 경로는 인증 없이 접근할 수 없다")
	void excludedAdminPublicPathsRequireAuthentication() throws Exception {
		mockMvc.perform(post("/api/admin/auth/password-reset/request")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/admin/unauthenticated"));

		mockMvc.perform(get("/api/admin/accounts/email-change/confirm")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value(SecurityProblemType.UNAUTHENTICATED.type().toString()));
	}

	@Test
	@DisplayName("상태를 변경하는 관리자 API 요청은 관리자 Origin만 허용한다")
	void adminStateChangingRequestsRequireAllowedOrigin() throws Exception {
		mockMvc.perform(post("/api/admin/auth/temporary-login")
						.header(HttpHeaders.ORIGIN, "https://www.pikume.com")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/admin/origin-forbidden"))
				.andExpect(jsonPath("$.status").value(403));
	}

	private void storeAnonymousSession(String rawSession, String rawCsrf) {
		LocalDateTime now = LocalDateTime.now();
		adminSessionJpaRepository.saveAndFlush(AdminSession.startAnonymous(
				adminSessionCredentialPort.hash(rawSession),
				adminSessionCredentialPort.hash(rawCsrf),
				now,
				now.plusMinutes(10)));
	}
}
