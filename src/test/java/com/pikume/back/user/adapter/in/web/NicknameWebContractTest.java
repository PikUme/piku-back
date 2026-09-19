package com.pikume.back.user.adapter.in.web;

import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.exception.GlobalExceptionHandler;
import com.pikume.back.global.port.out.ResolveObjectUrlPort;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.user.adapter.out.memory.InMemoryNicknameHoldAdapter;
import com.pikume.back.user.application.port.in.QueryUserProfileUseCase;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.LoadUserForProfilePort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.application.port.out.ResolveFixedCharacterAvatarPort;
import com.pikume.back.user.application.service.UserProfileCommandService;
import com.pikume.back.user.auth.adapter.in.web.AuthController;
import com.pikume.back.user.auth.adapter.in.web.AuthExceptionHandler;
import com.pikume.back.user.auth.application.port.in.QueryAllowedEmailUseCase;
import com.pikume.back.user.auth.application.port.out.CheckSignUpCharacterSelectionPort;
import com.pikume.back.user.auth.application.port.out.IssueVerificationEmailPort;
import com.pikume.back.user.auth.application.port.out.LoadCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.port.out.LoadVerificationPort;
import com.pikume.back.user.auth.application.port.out.ManageVerificationPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.RecordCompletedEmailVerificationPort;
import com.pikume.back.user.auth.application.service.AuthService;
import com.pikume.back.user.auth.domain.VerifiedEmail;
import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.service.NicknamePolicy;
import com.pikume.back.user.domain.service.PasswordPolicy;
import com.pikume.back.user.domain.vo.Nickname;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("닉네임 Web 계약")
class NicknameWebContractTest {

	private static final String USER_ID = "user-1";
	private static final String VALIDATION_TYPE =
			"https://api.pikume.com/problems/validation/invalid-request";

	private MockMvc mockMvc;
	private TestUserAccountStore userAccountStore;
	private InMemoryNicknameHoldAdapter nicknameHoldAdapter;
	private LoadCompletedEmailVerificationPort loadCompletedEmailVerificationPort;
	private CheckSignUpCharacterSelectionPort checkSignUpCharacterSelectionPort;
	private PasswordProtectionPort passwordProtectionPort;
	private ResolveFixedCharacterAvatarPort fixedCharacterAvatarPort;
	private ResolveObjectUrlPort resolveObjectUrlPort;

	@BeforeEach
	void setUp() {
		userAccountStore = new TestUserAccountStore();
		nicknameHoldAdapter = new InMemoryNicknameHoldAdapter(new NicknamePolicy());
		loadCompletedEmailVerificationPort = mock(LoadCompletedEmailVerificationPort.class);
		checkSignUpCharacterSelectionPort = mock(CheckSignUpCharacterSelectionPort.class);
		passwordProtectionPort = mock(PasswordProtectionPort.class);
		fixedCharacterAvatarPort = mock(ResolveFixedCharacterAvatarPort.class);
		resolveObjectUrlPort = mock(ResolveObjectUrlPort.class);

		QueryAllowedEmailUseCase queryAllowedEmailUseCase = mock(QueryAllowedEmailUseCase.class);
		AuthService authService = new AuthService(
				mock(LoadUserForPasswordResetPort.class),
				userAccountStore,
				userAccountStore,
				mock(LoadVerificationPort.class),
				mock(ManageVerificationPort.class),
				loadCompletedEmailVerificationPort,
				mock(RecordCompletedEmailVerificationPort.class),
				mock(IssueVerificationEmailPort.class),
				passwordProtectionPort,
				checkSignUpCharacterSelectionPort,
				queryAllowedEmailUseCase,
				new EmailVerificationPolicy(),
				new PasswordPolicy());
		UserProfileCommandService profileService = new UserProfileCommandService(
				userAccountStore,
				userAccountStore,
				userAccountStore,
				fixedCharacterAvatarPort,
				nicknameHoldAdapter);

		ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();
		AuthController authController = new AuthController(
				authService,
				authService,
				authService,
				queryAllowedEmailUseCase);
		UserController userController = new UserController(
				mock(QueryUserProfileUseCase.class),
				profileService,
				profileService,
				problemDetailFactory,
				resolveObjectUrlPort);
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(authController, userController)
				.setControllerAdvice(
						new GlobalExceptionHandler(Optional.empty(), problemDetailFactory),
						new AuthExceptionHandler(problemDetailFactory),
						new UserExceptionHandler(problemDetailFactory))
				.setCustomArgumentResolvers(new AuthenticationPrincipalResolver())
				.setValidator(validator)
				.build();
	}

	@Nested
	@DisplayName("POST /api/auth/signup")
	class Signup {

		@Test
		@DisplayName("원문은 20자를 초과해도 정규화한 20자 닉네임으로 가입한다")
		void acceptsRawNicknameLongerThanTwentyWhenNormalizedLengthIsTwenty() throws Exception {
			prepareSignup("user@example.com");

			mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"abc@123","nickname":" 12345678901234567890 ","fixedCharacterId":1}
								"""))
					.andExpect(status().isCreated())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
					.andExpect(jsonPath("$.message").value("회원가입 성공"));

			assertThat(userAccountStore.lastSignedUpUser().getNickname()).isEqualTo("12345678901234567890");
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidNicknames")
		@DisplayName("빈 값, 공백과 정규화 후 길이 초과를 400으로 거절한다")
		void rejectsInvalidNicknames(String ignored, String nickname, String detail) throws Exception {
			ResultActions result = mockMvc.perform(post("/api/auth/signup")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"email":"user@example.com","password":"abc@123","nickname":"%s","fixedCharacterId":1}
							""".formatted(nickname)));

			assertValidationProblem(result, detail, "/api/auth/signup");
		}

		@Test
		@DisplayName("닉네임 필드가 없으면 400으로 거절한다")
		void requiresNickname() throws Exception {
			ResultActions result = mockMvc.perform(post("/api/auth/signup")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"email":"user@example.com","password":"abc@123","fixedCharacterId":1}
							"""));

			assertValidationProblem(result, "닉네임은 필수 값입니다.", "/api/auth/signup");
		}

		@Test
		@DisplayName("저장 경계의 닉네임 중복이면 기존 nickname-conflict 409 구조를 반환한다")
		void keepsNicknameConflictResponseShape() throws Exception {
			prepareSignup("user@example.com");
			userAccountStore.rejectNextSignupNicknameAsDuplicate();

			mockMvc.perform(post("/api/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"abc@123","nickname":" 중복닉 ","fixedCharacterId":1}
								"""))
					.andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.type").value(
							"https://api.pikume.com/problems/user/nickname-conflict"))
					.andExpect(jsonPath("$.title").value("Conflict"))
					.andExpect(jsonPath("$.status").value(409))
					.andExpect(jsonPath("$.detail").value("이미 사용 중인 닉네임입니다."))
					.andExpect(jsonPath("$.instance").value("/api/auth/signup"));
		}

		private static Stream<Arguments> invalidNicknames() {
			return Stream.of(
					Arguments.of("빈 닉네임", "", "닉네임은 필수 값입니다."),
					Arguments.of("whitespace 닉네임", "   ", "닉네임은 필수 값입니다."),
					Arguments.of("21자 닉네임", "123456789012345678901", "닉네임은 1~20자 사이여야 합니다."));
		}
	}

	@Nested
	@DisplayName("GET /api/users/nickname/availability")
	class Availability {

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidNicknames")
		@DisplayName("빈 값, 공백과 정규화 후 길이 초과를 400으로 거절한다")
		void rejectsInvalidNicknames(String ignored, String nickname, String detail) throws Exception {
			ResultActions result = mockMvc.perform(get("/api/users/nickname/availability")
					.param("nickname", nickname));

			assertValidationProblem(result, detail, "/api/users/nickname/availability");
		}

		@Test
		@DisplayName("nickname 요청 파라미터가 없으면 400으로 거절한다")
		void requiresNicknameParameter() throws Exception {
			mockMvc.perform(get("/api/users/nickname/availability"))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
					.andExpect(jsonPath("$.title").value("Bad Request"))
					.andExpect(jsonPath("$.status").value(400))
					.andExpect(jsonPath("$.detail").value("요청 값이 올바르지 않습니다."))
					.andExpect(jsonPath("$.instance").value("/api/users/nickname/availability"));
		}

		@Test
		@DisplayName("사용 가능한 닉네임이면 기존 성공 구조를 반환한다")
		void keepsSuccessResponseShape() throws Exception {
			mockMvc.perform(get("/api/users/nickname/availability")
						.param("nickname", "새닉"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("사용 가능한 닉네임입니다."));
		}

		@Test
		@DisplayName("정규화 후 이미 사용 중인 닉네임이면 기존 409 구조를 반환한다")
		void keepsNicknameConflictResponseShape() throws Exception {
			userAccountStore.markNicknameInUse("중복닉");

			mockMvc.perform(get("/api/users/nickname/availability")
						.param("nickname", "  중복닉　 "))
					.andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.type").value(
							"https://api.pikume.com/problems/user/nickname-conflict"))
					.andExpect(jsonPath("$.title").value("Conflict"))
					.andExpect(jsonPath("$.status").value(409))
					.andExpect(jsonPath("$.detail").value("이미 사용 중인 닉네임입니다."))
					.andExpect(jsonPath("$.instance").value("/api/users/nickname/availability"));
		}

		private static Stream<Arguments> invalidNicknames() {
			return Signup.invalidNicknames();
		}
	}

	@Nested
	@DisplayName("PATCH /api/users/profile")
	class Profile {

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidNicknames")
		@DisplayName("빈 값, 공백과 정규화 후 길이 초과를 400으로 거절한다")
		void rejectsInvalidNicknames(String ignored, String nickname, String detail) throws Exception {
			ResultActions result = mockMvc.perform(patch("/api/users/profile")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"newNickname":"%s","characterId":2}
							""".formatted(nickname)));

			assertValidationProblem(result, detail, "/api/users/profile");
			assertThat(userAccountStore.profileUser().getCharacterId()).isEqualTo(1L);
		}

		@Test
		@DisplayName("null 닉네임은 변경 없음으로 유지하고 캐릭터만 변경한다")
		void preservesNullNicknameSemantics() throws Exception {
			given(fixedCharacterAvatarPort.resolveFixedCharacterObjectKey(2L))
					.willReturn(Optional.of("avatar-key"));
			given(resolveObjectUrlPort.resolveObjectUrl("avatar-key", true))
					.willReturn("https://assets.example.com/avatar.webp");

			mockMvc.perform(patch("/api/users/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"newNickname":null,"characterId":2}
								"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("캐릭터가 성공적으로 변경되었습니다."))
					.andExpect(jsonPath("$.newNickname").value("현재닉"))
					.andExpect(jsonPath("$.avatar").value("https://assets.example.com/avatar.webp"));

			assertThat(userAccountStore.profileUser().getNickname()).isEqualTo("현재닉");
			assertThat(userAccountStore.profileUser().getCharacterId()).isEqualTo(2L);
		}

		@Test
		@DisplayName("공백 형태가 다른 예약과 변경을 같은 닉네임으로 처리한다")
		void usesNormalizedNicknameAcrossReservationAndUpdate() throws Exception {
			mockMvc.perform(get("/api/users/nickname/availability")
						.param("nickname", "  새닉　 "))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("사용 가능한 닉네임입니다."));

			mockMvc.perform(patch("/api/users/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"newNickname":"\\t새닉\\n","characterId":null}
								"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.message").value("닉네임이 성공적으로 변경되었습니다."))
					.andExpect(jsonPath("$.newNickname").value("새닉"))
					.andExpect(jsonPath("$.avatar").doesNotExist());

			assertThat(userAccountStore.profileUser().getNickname()).isEqualTo("새닉");
		}

		@Test
		@DisplayName("다른 사용자가 점유한 닉네임이면 기존 profile-conflict 409 구조를 반환한다")
		void keepsHoldConflictResponseShape() throws Exception {
			nicknameHoldAdapter.tryAcquire(new Nickname("경쟁닉"), "other-user", java.time.Instant.now());

			mockMvc.perform(patch("/api/users/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"newNickname":"경쟁닉","characterId":null}
								"""))
					.andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.type").value(
							"https://api.pikume.com/problems/user/profile-conflict"))
					.andExpect(jsonPath("$.title").value("Conflict"))
					.andExpect(jsonPath("$.status").value(409))
					.andExpect(jsonPath("$.detail").value(
							"닉네임 점유 정보가 없거나 만료되었거나 본인이 아닙니다."))
					.andExpect(jsonPath("$.instance").value("/api/users/profile"));
		}

		@Test
		@DisplayName("예약 후 사용 중이 된 닉네임이면 기존 nickname-conflict 409 구조를 반환한다")
		void keepsPersistenceConflictResponseShape() throws Exception {
			mockMvc.perform(get("/api/users/nickname/availability")
						.param("nickname", "중복닉"))
					.andExpect(status().isOk());
			userAccountStore.markNicknameInUse("중복닉");

			mockMvc.perform(patch("/api/users/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"newNickname":"중복닉","characterId":null}
								"""))
					.andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.type").value(
							"https://api.pikume.com/problems/user/nickname-conflict"))
					.andExpect(jsonPath("$.title").value("Conflict"))
					.andExpect(jsonPath("$.status").value(409))
					.andExpect(jsonPath("$.detail").value("이미 사용 중인 닉네임입니다."))
					.andExpect(jsonPath("$.instance").value("/api/users/profile"));
		}

		private static Stream<Arguments> invalidNicknames() {
			return Signup.invalidNicknames();
		}
	}

	private void prepareSignup(String email) {
		given(loadCompletedEmailVerificationPort.loadLatestVerification(email, VerificationType.SIGN_UP))
				.willReturn(Optional.of(new VerifiedEmail(email, VerificationType.SIGN_UP)));
		given(checkSignUpCharacterSelectionPort.isSelectableFixedCharacter(1L)).willReturn(true);
		given(passwordProtectionPort.protect("abc@123")).willReturn("encoded-password");
	}

	private void assertValidationProblem(ResultActions result, String detail, String instance) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value(VALIDATION_TYPE))
				.andExpect(jsonPath("$.title").value("Bad Request"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value(detail))
				.andExpect(jsonPath("$.instance").value(instance));
	}

	private record AuthenticationPrincipalResolver() implements HandlerMethodArgumentResolver {
		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
					&& parameter.getParameterType().equals(UserPrincipal.class);
		}

		@Override
		public Object resolveArgument(
				@NotNull MethodParameter parameter,
				ModelAndViewContainer mavContainer,
				@NotNull NativeWebRequest webRequest,
				WebDataBinderFactory binderFactory) {
			return new UserPrincipal(USER_ID, "현재닉");
		}
	}

	private static final class TestUserAccountStore implements
			LoadUserForProfilePort, RecordUserAccountPort, CheckUserUniquenessPort {

		private final Set<Nickname> usedNicknames = new HashSet<>();
		private User profileUser = new User(USER_ID, "user@example.com", "encoded-password", "현재닉", 1L);
		private User lastSignedUpUser;
		private boolean rejectNextSignupNicknameAsDuplicate;

		@Override
		public Optional<User> loadProfileUser(String userId) {
			return USER_ID.equals(userId) ? Optional.of(profileUser) : Optional.empty();
		}

		@Override
		public User recordUserAccount(User user) {
			if (USER_ID.equals(user.getId())) {
				profileUser = user;
			} else {
				if (rejectNextSignupNicknameAsDuplicate) {
					throw new NicknameAlreadyExistsException(user.getNickname());
				}
				lastSignedUpUser = user;
			}
			return user;
		}

		@Override
		public boolean isNicknameInUse(Nickname nickname) {
			return usedNicknames.contains(nickname);
		}

		@Override
		public boolean isEmailRegistered(String email) {
			return false;
		}

		void markNicknameInUse(String nickname) {
			usedNicknames.add(new Nickname(nickname));
		}

		void rejectNextSignupNicknameAsDuplicate() {
			rejectNextSignupNicknameAsDuplicate = true;
		}

		User profileUser() {
			return profileUser;
		}

		User lastSignedUpUser() {
			return lastSignedUpUser;
		}
	}
}
