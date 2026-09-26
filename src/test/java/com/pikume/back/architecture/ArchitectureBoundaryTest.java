package com.pikume.back.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Architecture boundaries")
class ArchitectureBoundaryTest {

	@Test
	@DisplayName("Controller 응답 타입과 중첩 DTO는 Spring Data Page를 노출하지 않는다.")
	void controllerResponsesDoNotExposeSpringDataPages() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		List<String> violations = new ArrayList<>();

		for (var bean : scanner.findCandidateComponents("com.pikume.back")) {
			Class<?> controller = Class.forName(bean.getBeanClassName());
			for (var method : controller.getDeclaredMethods()) {
				if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
					findPageLeaks(method.getGenericReturnType(), method.toGenericString(), new HashSet<>(), violations);
				}
			}
		}

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("응답 래퍼 내부 DTO에 숨은 Page도 경계 위반으로 찾는다.")
	void pageBoundaryGuardFindsWrappedNestedPages() throws NoSuchMethodException {
		Type leakingReturnType = ArchitectureBoundaryTest.class
				.getDeclaredMethod("leakingResponseFixture")
				.getGenericReturnType();
		List<String> violations = new ArrayList<>();

		findPageLeaks(leakingReturnType, "controlled mutation", new HashSet<>(), violations);

		assertThat(violations).singleElement()
				.satisfies(violation -> assertThat(violation).contains("NestedPageFixture.page", Page.class.getName()));
	}

	@Test
	@DisplayName("security outbound adapters는 user persistence adapters 또는 user domain entities에 대한 의존성을 갖지 않는다.")
	void securityOutboundAdaptersDoNotDependOnUserPersistenceOrDomain() throws IOException {
		Path securityOutboundAdapters = Path.of("src/main/java/com/pikume/back/security/adapter/out");
		List<String> violations = findJavaSourceViolations(
				securityOutboundAdapters,
				this::importsUserPersistenceOrDomain);

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("user domain은 application, adapter, 다른 context 또는 Spring 기술에 의존하지 않는다.")
	void userDomainDoesNotDependOnOutsideLayersOrContexts() throws IOException {
		Path userSources = Path.of("src/main/java/com/pikume/back/user");

		assertThat(findJavaSourceViolations(
				userSources,
				path -> path.toString().contains("/domain/")
						&& (sourceContains(path, "com.pikume.back.user.application")
						|| sourceContains(path, "com.pikume.back.user.adapter")
						|| sourceContains(path, "com.pikume.back.character.")
						|| sourceContains(path, "com.pikume.back.diary.")
						|| sourceContains(path, "com.pikume.back.social.")
						|| sourceContains(path, "com.pikume.back.notification.")
						|| sourceContains(path, "org.springframework.")
						|| sourceContains(path, "io.jsonwebtoken.")
						|| sourceContains(path, "jakarta.servlet."))))
				.isEmpty();
	}

	@Test
	@DisplayName("user application은 Character 내부 모델에 직접 의존하지 않는다.")
	void userApplicationDependsOnCharacterOnlyThroughAdapters() throws IOException {
		Path userApplication = Path.of("src/main/java/com/pikume/back/user/application");
		Path userAuthApplication = Path.of("src/main/java/com/pikume/back/user/auth/application");

		assertThat(findJavaSourceViolations(
				userApplication,
				path -> sourceContains(path, "com.pikume.back.character.")))
				.isEmpty();
		assertThat(findJavaSourceViolations(
				userAuthApplication,
				path -> sourceContains(path, "com.pikume.back.character.")))
				.isEmpty();
	}

	@Test
	@DisplayName("creative application은 웹 보안 principal에 의존하지 않는다.")
	void creativeApplicationDoesNotDependOnWebSecurityPrincipal() throws IOException {
		Path creativeApplication = Path.of("src/main/java/com/pikume/back/creative/application");

		assertThat(findJavaSourceViolations(
				creativeApplication,
				path -> sourceContains(path, "com.pikume.back.security.principal.UserPrincipal")))
				.isEmpty();
	}

	@Test
	@DisplayName("creative outbound cross-context adapter는 crosscontext 폴더에 위치한다.")
	void creativeCrossContextAdaptersUseStandardFolder() throws IOException {
		Path creativeOutboundAdapters = Path.of("src/main/java/com/pikume/back/creative/adapter/out");

		assertThat(findJavaSourceViolations(
				creativeOutboundAdapters,
				path -> path.toString().contains("/adapter/out/user/")))
				.isEmpty();
	}

	@Test
	@DisplayName("diary outbound ports는 creative 도메인의 타입을 노출하지 않는다.")
	void diaryOutboundPortsDoNotExposeCreativeTypes() throws IOException {
		Path diaryOutboundPorts = Path.of("src/main/java/com/pikume/back/diary/application/port/out");

		assertThat(findJavaSourceViolations(
				diaryOutboundPorts,
				path -> sourceContains(path, "import com.pikume.back.creative.")))
				.isEmpty();
	}

	@Test
	@DisplayName("creative ports는 저장소 관용 메서드명을 노출하지 않는다.")
	void creativePortsDoNotExposeRepositoryMethodNames() throws IOException {
		Path creativePorts = Path.of("src/main/java/com/pikume/back/creative/application/port");
		List<String> repositoryMethodNames = List.of(
				" findById(",
				" findByDiaryIdIsNull(",
				" findByUserIdAndFilePath(",
				" existsByIdAndUserId(",
				" save(",
				" saveAIPhoto(");

		assertThat(findJavaSourceViolations(
				creativePorts,
				path -> repositoryMethodNames.stream().anyMatch(name -> sourceContains(path, name))))
				.isEmpty();
	}

	@Test
	@DisplayName("프로덕션 코드는 사용하지 않는 HTTP 요청 메타데이터 계약에 의존하지 않는다.")
	void productionCodeDoesNotDependOnUnusedRequestMetadata() throws IOException {
		Path productionSources = Path.of("src/main/java");

		assertThat(findJavaSourceViolations(
				productionSources,
				path -> sourceContains(path, "RequestMetaInfo")
						|| sourceContains(path, "RequestMetaMapper")))
				.isEmpty();
	}

	@Test
	@DisplayName("프로덕션 로그 포맷은 raw 인증 값과 개인정보 필드를 선언하지 않는다.")
	void productionLogFormatsDoNotDeclareSensitiveFields() throws IOException {
		Path productionSources = Path.of("src/main/java");
		List<String> forbiddenLogFragments = List.of(
				"email={}",
				"email= {}",
				"이메일={}",
				"password={}",
				"비밀번호={}",
				"code={}",
				"인증코드={}",
				"accessToken={}",
				"refreshToken={}",
				"authorization={}",
				"deviceId={}",
				"deviceId: {}",
				"clientIp={}",
				"IP: {}",
				"User-Agent: {}",
				"new EmbedField(\"Client-IP\"",
				"new EmbedField(\"User-Agent\"",
				"new EmbedField(\"Error-Message\"",
				"new EmbedField(\"Stack-Trace\"",
				"token={}",
				"token: {}",
				"토큰 삭제: {}");

		assertThat(findJavaSourceViolations(
				productionSources,
				path -> forbiddenLogFragments.stream().anyMatch(fragment -> sourceContains(path, fragment))))
				.isEmpty();
	}

	@Test
	@DisplayName("다른 context는 user outbound port에 직접 의존하지 않는다.")
	void otherContextsDoNotDependOnUserOutboundPorts() throws IOException {
		Path productionSources = Path.of("src/main/java/com/pikume/back");

		assertThat(findJavaSourceViolations(
				productionSources,
				path -> !path.toString().contains("/user/")
						&& sourceContains(path, "com.pikume.back.user.application.port.out")))
				.isEmpty();
	}

	@Test
	@DisplayName("user application은 범용 persistence port를 사용하지 않는다.")
	void userApplicationDoesNotUseGenericPersistencePorts() throws IOException {
		Path userApplication = Path.of("src/main/java/com/pikume/back/user/application");

		assertThat(findJavaSourceViolations(
				userApplication,
				path -> sourceContains(path, "LoadUserPort")
						|| sourceContains(path, "UserQueryPort")))
				.isEmpty();
	}

	@Test
	@DisplayName("user inbound ports는 유스케이스 의도 중심 이름을 사용한다.")
	void userInboundPortsUseIntentionRevealingNames() throws IOException {
		Path userInboundPorts = Path.of("src/main/java/com/pikume/back/user/application/port/in");

		assertThat(Files.exists(userInboundPorts.resolve("ReserveNicknameUseCase.java"))).isTrue();
		assertThat(Files.exists(userInboundPorts.resolve("QueryUserProfileUseCase.java"))).isTrue();
		assertThat(Files.exists(userInboundPorts.resolve("UpdateUserProfileUseCase.java"))).isTrue();

		List<String> obsoleteNames = List.of(
				"CheckNicknameUseCase",
				"GetUserProfileUseCase",
				"UpdateProfileUseCase",
				" checkAvailability(",
				" getProfilePreview(",
				" getUserProfile(",
				" findByEmail(",
				" findById(",
				" findUserReference(",
				" getUserReference(",
				" getUserSummaries(",
				" searchByKeyword(");

		assertThat(findJavaSourceViolations(
				userInboundPorts,
				path -> obsoleteNames.stream().anyMatch(name -> sourceContains(path, name))))
				.isEmpty();
	}

	@Test
	@DisplayName("user account persistence ports는 application 목적별 능력을 노출한다.")
	void userAccountPersistencePortsExposeApplicationPurposes() throws IOException {
		Path userOutboundPorts = Path.of("src/main/java/com/pikume/back/user/application/port/out");
		Path userServices = Path.of("src/main/java/com/pikume/back/user/application/service");
		Path authService = Path.of("src/main/java/com/pikume/back/user/auth/application/service/AuthService.java");
		Path persistenceAdapter = Path.of(
				"src/main/java/com/pikume/back/user/adapter/out/persistence/UserAccountPersistenceAdapter.java");

		for (String portFile : List.of(
				"LoadUserForProfilePort.java",
				"LoadUserForAuthenticationPort.java",
				"LoadUserForPasswordResetPort.java",
				"LoadUserReferencePort.java",
				"RecordUserAccountPort.java")) {
			assertThat(Files.exists(userOutboundPorts.resolve(portFile))).isTrue();
		}
		assertThat(Files.exists(userOutboundPorts.resolve("LoadUserAccountPort.java"))).isFalse();
		assertThat(Files.exists(userOutboundPorts.resolve("SaveUserPort.java"))).isFalse();

		List<String> purposeSpecificLoadPorts = List.of(
				"LoadUserForProfilePort",
				"LoadUserForAuthenticationPort",
				"LoadUserForPasswordResetPort",
				"LoadUserReferencePort");
		assertUsesOnlyPurposeSpecificUserLoadPort(
				userServices.resolve("UserProfileCommandService.java"),
				"LoadUserForProfilePort",
				purposeSpecificLoadPorts);
		assertUsesOnlyPurposeSpecificUserLoadPort(
				userServices.resolve("UserProfileQueryService.java"),
				"LoadUserForProfilePort",
				purposeSpecificLoadPorts);
		assertUsesOnlyPurposeSpecificUserLoadPort(
				userServices.resolve("UserIdentityQueryService.java"),
				"LoadUserForAuthenticationPort",
				purposeSpecificLoadPorts);
		assertUsesOnlyPurposeSpecificUserLoadPort(
				userServices.resolve("UserReferenceQueryService.java"),
				"LoadUserReferencePort",
				purposeSpecificLoadPorts);
		assertUsesOnlyPurposeSpecificUserLoadPort(
				userServices.resolve("UserSummaryQueryService.java"),
				"LoadUserReferencePort",
				purposeSpecificLoadPorts);
		assertUsesOnlyPurposeSpecificUserLoadPort(
				authService,
				"LoadUserForPasswordResetPort",
				purposeSpecificLoadPorts);

		for (String implementedPort : List.of(
				"LoadUserForProfilePort",
				"LoadUserForAuthenticationPort",
				"LoadUserForPasswordResetPort",
				"LoadUserReferencePort",
				"CheckUserUniquenessPort")) {
			assertThat(sourceContains(persistenceAdapter, implementedPort)).isTrue();
		}

		List<String> repositoryNames = List.of(
				" existsByNickname(",
				" existsByEmail(",
				" searchByName(",
				" save(");
		assertThat(findJavaSourceViolations(
				userOutboundPorts,
				path -> repositoryNames.stream().anyMatch(name -> sourceContains(path, name))))
				.isEmpty();
	}

	@Test
	@DisplayName("user cross-context ports는 user application 관점의 능력을 표현한다.")
	void userCrossContextPortsExpressUserApplicationCapabilities() throws IOException {
		Path userOutboundPorts = Path.of("src/main/java/com/pikume/back/user/application/port/out");
		Path userCrossContextAdapters = Path.of("src/main/java/com/pikume/back/user/adapter/out/crosscontext");

		assertThat(Files.exists(userOutboundPorts.resolve("ResolveFixedCharacterAvatarPort.java"))).isTrue();
		assertThat(Files.exists(userOutboundPorts.resolve("ResolveAvatarCharacterReferencesPort.java"))).isTrue();
		assertThat(Files.exists(userOutboundPorts.resolve("LoadFixedCharacterPort.java"))).isFalse();

		List<String> obsoleteNames = List.of(
				" findFixedCharacterObjectKey(",
				" countVisibleDiaries(",
				" getVisibleMonthlyDiaryCounts(",
				" countFriends(",
				" getFriendshipStatus(");
		assertThat(findJavaSourceViolations(
				userOutboundPorts,
				path -> obsoleteNames.stream().anyMatch(name -> sourceContains(path, name))))
				.isEmpty();

		assertThat(sourceContains(
				userCrossContextAdapters.resolve("CharacterAdapterForUser.java"),
				"ResolveFixedCharacterAvatarPort")).isTrue();
		assertThat(sourceContains(
				userCrossContextAdapters.resolve("CharacterAdapterForUser.java"),
				"ResolveAvatarCharacterReferencesPort")).isTrue();
	}

	@Test
	@DisplayName("user auth inbound ports는 인증 유스케이스 의도를 표현한다.")
	void userAuthInboundPortsExpressAuthenticationUseCases() throws IOException {
		Path userAuthInboundPorts = Path.of("src/main/java/com/pikume/back/user/auth/application/port/in");
		List<String> obsoleteNames = List.of(
				" logoutByRefreshToken(",
				" getAllowedEmailDomains(",
				" verifyCodeAndResetPwd(",
				" signup(");

		assertThat(findJavaSourceViolations(
				userAuthInboundPorts,
				path -> obsoleteNames.stream().anyMatch(name -> sourceContains(path, name))))
				.isEmpty();
	}

	@Test
	@DisplayName("user auth outbound ports는 인증 application 능력을 표현한다.")
	void userAuthOutboundPortsExpressAuthenticationCapabilities() throws IOException {
		Path userAuthOutboundPorts = Path.of("src/main/java/com/pikume/back/user/auth/application/port/out");

		for (String portFile : List.of(
				"CheckSignUpCharacterSelectionPort.java",
				"LoadCompletedEmailVerificationPort.java",
				"ManageVerificationPort.java",
				"RecordCompletedEmailVerificationPort.java",
				"IssueVerificationEmailPort.java")) {
			assertThat(Files.exists(userAuthOutboundPorts.resolve(portFile))).isTrue();
		}
		for (String obsoleteFile : List.of(
				"ResolveSignUpAvatarPort.java",
				"LoadFixedCharacterForSignUpPort.java",
				"LoadVerifiedEmailPort.java",
				"SaveVerificationPort.java",
				"SaveVerifiedEmailPort.java",
				"SendVerificationEmailPort.java")) {
			assertThat(Files.exists(userAuthOutboundPorts.resolve(obsoleteFile))).isFalse();
		}

		List<String> obsoleteNames = List.of(
				" isValid(",
				" existsByDomain(",
				" loadAllDomains(",
				" findFixedCharacterObjectKey(",
				" findByEmailAndType(",
				" findTopByEmailAndTypeOrderByVerifiedAtDesc(",
				" findByRefreshToken(",
				" save(",
				" deleteByRefreshToken(",
				" deleteByKey(",
				" delete(",
				" sendVerificationEmail(");
		assertThat(findJavaSourceViolations(
				userAuthOutboundPorts,
				path -> obsoleteNames.stream().anyMatch(name -> sourceContains(path, name))))
				.isEmpty();
	}

	@Test
	@DisplayName("user와 user auth ports는 표준 패키지와 접미사를 사용한다.")
	void userPortsUseStandardPackagesAndSuffixes() throws IOException {
		for (Path inboundPorts : List.of(
				Path.of("src/main/java/com/pikume/back/user/application/port/in"),
				Path.of("src/main/java/com/pikume/back/user/auth/application/port/in"))) {
			assertThat(findJavaSourceViolations(
					inboundPorts,
					path -> !path.getFileName().toString().endsWith("UseCase.java")))
					.isEmpty();
		}

		for (Path outboundPorts : List.of(
				Path.of("src/main/java/com/pikume/back/user/application/port/out"),
				Path.of("src/main/java/com/pikume/back/user/auth/application/port/out"))) {
			assertThat(findJavaSourceViolations(
					outboundPorts,
					path -> !path.getFileName().toString().endsWith("Port.java")))
					.isEmpty();
		}
	}

	@Test
	@DisplayName("feed cross-context adapter는 표준 위치와 Provider 공개 계약을 사용한다.")
	void feedAdaptersUsePublicProviderContractsFromStandardFolder() throws IOException {
		Path feedOutboundAdapters = Path.of("src/main/java/com/pikume/back/feed/adapter/out");
		Path feedCrossContextAdapters = feedOutboundAdapters.resolve("crosscontext");

		assertThat(findJavaSourceViolations(
				feedOutboundAdapters,
				path -> path.toString().contains("/adapter/out/user/")
						|| path.toString().contains("/adapter/out/diary/")
						|| path.toString().contains("/adapter/out/social/")
						|| path.toString().contains("/adapter/out/recommendation/")))
				.isEmpty();
		assertThat(findJavaSourceViolations(
				feedCrossContextAdapters,
				path -> sourceContains(path, ".adapter.out.persistence")
						|| sourceContains(path, "com.pikume.back.diary.application.port.out")
						|| sourceContains(path, "com.pikume.back.social.application.port.out")
						|| sourceContains(path, "com.pikume.back.user.application.port.out")
						|| sourceContains(path, "com.pikume.back.recommendation.application.port.out")))
				.isEmpty();
	}

	@Test
	@DisplayName("user application은 이미지 URL 변환과 cross-context adapter 구현에 의존하지 않는다.")
	void userApplicationKeepsWebAndCrossContextDetailsOutside() throws IOException {
		Path userApplication = Path.of("src/main/java/com/pikume/back/user/application");
		Path userOutboundAdapters = Path.of("src/main/java/com/pikume/back/user/adapter/out");

		assertThat(findJavaSourceViolations(
				userApplication,
				path -> sourceContains(path, "ImagePathToUrlConverter")))
				.isEmpty();
		assertThat(findJavaSourceViolations(
				userOutboundAdapters,
				path -> path.toString().contains("/adapter/out/character/")
						|| path.toString().contains("/adapter/out/diary/")
						|| path.toString().contains("/adapter/out/friend/")))
				.isEmpty();
	}

	@Test
	@DisplayName("user auth application과 web adapter는 계층 경계를 지킨다.")
	void userAuthKeepsWebSecurityAndOutboundDetailsOutside() throws IOException {
		Path authApplication = Path.of("src/main/java/com/pikume/back/user/auth/application");
		Path authWebAdapter = Path.of("src/main/java/com/pikume/back/user/auth/adapter/in/web");

		assertThat(findJavaSourceViolations(
				authApplication,
				path -> sourceContains(path, "user.auth.dto.request")
						|| sourceContains(path, "PasswordEncoder")))
				.isEmpty();
		assertThat(findJavaSourceViolations(
				authWebAdapter,
				path -> sourceContains(path, "user.auth.application.port.out")))
				.isEmpty();
	}

	@Test
	@DisplayName("user application은 Web, Security 구현, JWT, Repository와 다른 context 내부 모델에 의존하지 않는다.")
	void userApplicationUsesOnlyAllowedBoundaries() throws IOException {
		Path userApplication = Path.of("src/main/java/com/pikume/back/user/application");
		Path userAuthApplication = Path.of("src/main/java/com/pikume/back/user/auth/application");

		for (Path root : List.of(userApplication, userAuthApplication)) {
			assertThat(findJavaSourceViolations(root, path ->
					sourceContains(path, "jakarta.servlet")
							|| sourceContains(path, "org.springframework.security")
							|| sourceContains(path, "io.jsonwebtoken")
							|| sourceContains(path, "JpaRepository")
							|| sourceContains(path, "adapter.out.persistence")
							|| sourceContains(path, "com.pikume.back.character.")
							|| sourceContains(path, "com.pikume.back.diary.")
							|| sourceContains(path, "com.pikume.back.social.")
							|| sourceContains(path, "com.pikume.back.notification.")))
					.isEmpty();
		}
	}

	@Test
	@DisplayName("security 기술 모듈은 일반 사용자 application service와 domain 모델을 소유하지 않는다.")
	void securityDoesNotOwnUserUseCasesOrDomainModels() throws IOException {
		for (Path removedLayer : List.of(
				Path.of("src/main/java/com/pikume/back/security/application"),
				Path.of("src/main/java/com/pikume/back/security/domain"))) {
			if (Files.exists(removedLayer)) {
				assertThat(findJavaSourceViolations(removedLayer, path -> true)).isEmpty();
			}
		}
	}

	@Test
	@DisplayName("security principal은 Global이 아니라 Security가 소유하고 다른 context의 Web adapter에서만 사용한다.")
	void securityOwnsPrincipalsUsedByWebAdapters() throws IOException {
		Path productionSources = Path.of("src/main/java/com/pikume/back");
		Path globalPrincipal = productionSources.resolve("global/config/CustomUserDetails.java");

		assertThat(globalPrincipal).doesNotExist();
		assertThat(productionSources.resolve("security/principal/UserPrincipal.java")).exists();
		assertThat(productionSources.resolve("security/principal/AdminPrincipal.java")).exists();
		assertThat(findJavaSourceViolations(
				productionSources,
				path -> !path.toString().contains("/security/")
						&& sourceContains(path, "com.pikume.back.security.principal.")
						&& !path.toString().contains("/adapter/in/web/")))
				.isEmpty();
	}

	@Test
	@DisplayName("다른 context는 user 내부 모델과 persistence 경계를 import하지 않는다.")
	void otherContextsUseOnlyPublicUserContracts() throws IOException {
		Path productionSources = Path.of("src/main/java/com/pikume/back");

		assertThat(findJavaSourceViolations(productionSources, path ->
				!path.toString().contains("/user/")
						&& (sourceContains(path, "com.pikume.back.user.domain")
						|| sourceContains(path, "com.pikume.back.user.application.exception")
						|| sourceContains(path, "com.pikume.back.user.application.port.out")
						|| sourceContains(path, "com.pikume.back.user.adapter.out.persistence"))))
				.isEmpty();
	}

	@Test
	@DisplayName("다른 context는 notification의 Domain, Out Port와 Adapter에 의존하지 않는다.")
	void otherContextsUseOnlyPublicNotificationContracts() throws IOException {
		Path productionSources = Path.of("src/main/java/com/pikume/back");

		assertThat(findJavaSourceViolations(productionSources, path ->
				!path.toString().contains("/notification/")
						&& (sourceContains(path, "com.pikume.back.notification.domain")
						|| sourceContains(path, "com.pikume.back.notification.application.port.out")
						|| sourceContains(path, "com.pikume.back.notification.adapter."))))
				.isEmpty();
	}

	@Test
	@DisplayName("다른 context는 social의 Domain, Out Port와 Adapter에 의존하지 않는다.")
	void otherContextsUseOnlyPublicSocialContracts() throws IOException {
		Path productionSources = Path.of("src/main/java/com/pikume/back");

		assertThat(findJavaSourceViolations(productionSources, path ->
				!path.toString().contains("/social/")
						&& (sourceContains(path, "com.pikume.back.social.domain")
						|| sourceContains(path, "com.pikume.back.social.application.port.out")
						|| sourceContains(path, "com.pikume.back.social.adapter."))))
				.isEmpty();
	}

	private List<String> findJavaSourceViolations(Path root, Predicate<Path> violationPredicate) throws IOException {
		try (var paths = Files.walk(root)) {
			return paths
					.filter(path -> path.toString().endsWith(".java"))
					.filter(violationPredicate)
					.map(Path::toString)
					.toList();
		}
	}

	private void findPageLeaks(Type type, String path, Set<Type> visited, List<String> violations) {
		if (type == null || !visited.add(type)) {
			return;
		}
		if (type instanceof ParameterizedType parameterizedType) {
			findPageLeaks(parameterizedType.getRawType(), path, visited, violations);
			for (Type argument : parameterizedType.getActualTypeArguments()) {
				findPageLeaks(argument, path, visited, violations);
			}
			return;
		}
		if (type instanceof WildcardType wildcardType) {
			for (Type bound : wildcardType.getUpperBounds()) {
				findPageLeaks(bound, path, visited, violations);
			}
			for (Type bound : wildcardType.getLowerBounds()) {
				findPageLeaks(bound, path, visited, violations);
			}
			return;
		}
		if (type instanceof GenericArrayType arrayType) {
			findPageLeaks(arrayType.getGenericComponentType(), path, visited, violations);
			return;
		}
		if (!(type instanceof Class<?> typeClass)) {
			return;
		}
		if (Page.class.isAssignableFrom(typeClass)) {
			violations.add(path + " -> " + typeClass.getName());
			return;
		}
		if (typeClass.isArray()) {
			findPageLeaks(typeClass.getComponentType(), path, visited, violations);
			return;
		}
		if (!typeClass.getName().startsWith("com.pikume.back.")) {
			return;
		}
		for (Field field : typeClass.getDeclaredFields()) {
			if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
				findPageLeaks(field.getGenericType(), path + " -> " + typeClass.getSimpleName() + "." + field.getName(),
						visited, violations);
			}
		}
	}

	@SuppressWarnings("unused")
	private ResponseEntity<WrappedPageFixture> leakingResponseFixture() {
		return null;
	}

	private record WrappedPageFixture(NestedPageFixture body) {
	}

	private record NestedPageFixture(Page<String> page) {
	}

	private boolean importsUserPersistenceOrDomain(Path path) {
		return sourceContains(path, "com.pikume.back.user.adapter.out.persistence")
				|| sourceContains(path, "com.pikume.back.user.domain.User");
	}

	private void assertUsesOnlyPurposeSpecificUserLoadPort(
			Path service,
			String expectedPort,
			List<String> purposeSpecificLoadPorts) {
		assertThat(sourceContains(service, expectedPort)).isTrue();
		assertThat(purposeSpecificLoadPorts.stream()
				.filter(port -> !port.equals(expectedPort))
				.filter(port -> sourceContains(service, port)))
				.isEmpty();
	}

	private boolean sourceContains(Path path, String text) {
		try {
			return Files.readString(path).contains(text);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + path, e);
		}
	}
}
