package com.pikume.back.global.adapter.in.web.pagination;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.global.pagination.PageQuery;
import com.pikume.back.global.pagination.PageResult;
import com.pikume.back.global.pagination.SortQuery;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.social.application.dto.CommentListItemResult;
import com.pikume.back.social.application.dto.FriendSummaryResult;
import com.pikume.back.social.application.service.CommentQueryService;
import com.pikume.back.social.application.service.FriendQueryService;
import com.pikume.back.user.application.dto.UserAvatarReference;
import com.pikume.back.user.application.dto.UserSearchResult;
import com.pikume.back.user.application.service.UserSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.SpringDataJacksonConfiguration.PageModule;
import org.springframework.data.web.config.SpringDataWebSettings;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"spring.data.web.pageable.default-page-size=10",
		"spring.data.web.pageable.max-page-size=100"
})
@AutoConfigureMockMvc
@DisplayName("Offset page MVC contract")
class OffsetPageMvcIntegrationTest {

	private static final String PAGE_WARNING = "Serializing PageImpl instances as-is is not supported";
	private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
			"content", "pageable", "last", "totalPages", "totalElements", "size",
			"number", "sort", "first", "numberOfElements", "empty");
	private static final Set<String> PAGEABLE_FIELDS = Set.of(
			"pageNumber", "pageSize", "sort", "offset", "paged", "unpaged");
	private static final Set<String> SORT_FIELDS = Set.of("empty", "sorted", "unsorted");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private RequestMappingHandlerAdapter handlerAdapter;

	@MockitoBean
	private CommentQueryService queryCommentPageUseCase;
	@MockitoBean
	private FriendQueryService queryFriendPageUseCase;
	@MockitoBean
	private UserSearchService searchUserUseCase;

	private MappingJackson2HttpMessageConverter jsonConverter;
	private ObjectMapper originalObjectMapper;

	@BeforeEach
	void setUp() {
		jsonConverter = handlerAdapter.getMessageConverters().stream()
				.filter(MappingJackson2HttpMessageConverter.class::isInstance)
				.map(MappingJackson2HttpMessageConverter.class::cast)
				.findFirst()
				.orElseThrow();
		originalObjectMapper = jsonConverter.getObjectMapper();
	}

	@AfterEach
	void restoreObjectMapper() {
		jsonConverter.setObjectMapper(originalObjectMapper);
	}

	@Test
	@DisplayName("원댓글은 기본 내림차순과 익명·권한 필드를 기존 페이지 외형으로 반환한다")
	void rootCommentsPreserveContractWithoutPageWarning() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 7, 10, 30);
		PageQuery expectedQuery = PageQuery.of(0, 10, List.of(SortQuery.desc("createdAt")));
		given(queryCommentPageUseCase.queryRootCommentPage(41L, expectedQuery, "viewer-id"))
				.willReturn(new PageResult<>(List.of(
						new CommentListItemResult(101L, 41L, null, null, null, "익명 댓글", null,
								createdAt, createdAt, 2, true, false, false)), 0, 10, 12));

		JsonNode json = performWithFreshPageModule(get("/api/comments")
				.param("diaryId", "41")
				.with(user(new UserPrincipal("viewer-id", "viewer"))));

		assertPage(json, 0, 10, 12, 2, 1, true, false, false, true);
		assertThat(fieldNames(json.path("content").path(0))).containsExactlyInAnyOrder(
				"id", "diaryId", "userId", "nickname", "avatar", "content", "parentId",
				"createdAt", "updatedAt", "replyCount", "canReply", "canEdit", "canDelete");
		assertThat(json.at("/content/0/userId").isNull()).isTrue();
		assertThat(json.at("/content/0/nickname").isNull()).isTrue();
		assertThat(json.at("/content/0/avatar").isNull()).isTrue();
		assertThat(json.at("/content/0/content").asText()).isEqualTo("익명 댓글");
		assertThat(json.at("/content/0/canReply").asBoolean()).isTrue();
		assertThat(json.at("/content/0/canEdit").asBoolean()).isFalse();
		assertThat(json.at("/content/0/canDelete").asBoolean()).isFalse();
		then(queryCommentPageUseCase).should().queryRootCommentPage(41L, expectedQuery, "viewer-id");
	}

	@Test
	@DisplayName("답글은 명시적 정렬과 최대 페이지 크기를 적용해 순서를 보존한다")
	void repliesBindExplicitSortAndMaximumSize() throws Exception {
		LocalDateTime firstTime = LocalDateTime.of(2026, 9, 7, 11, 0);
		LocalDateTime secondTime = firstTime.plusMinutes(1);
		PageQuery expectedQuery = PageQuery.of(1, 100, List.of(SortQuery.desc("createdAt")));
		given(queryCommentPageUseCase.queryReplyPage(101L, expectedQuery, "viewer-id"))
				.willReturn(new PageResult<>(List.of(
						new CommentListItemResult(202L, 41L, "writer-2", "둘째", "/avatar-2", "두 번째", 101L,
								firstTime, firstTime, 0, true, true, true),
						new CommentListItemResult(201L, 41L, "writer-1", "첫째", "/avatar-1", "첫 번째", 101L,
								secondTime, secondTime, 0, true, false, false)), 1, 100, 250));

		JsonNode json = performWithFreshPageModule(get("/api/comments/101/replies")
				.param("page", "1")
				.param("size", "101")
				.param("sort", "createdAt,desc")
				.with(user(new UserPrincipal("viewer-id", "viewer"))));

		assertPage(json, 1, 100, 250, 3, 2, false, false, false, true);
		assertThat(json.at("/content/0/id").asLong()).isEqualTo(202L);
		assertThat(json.at("/content/1/id").asLong()).isEqualTo(201L);
		assertThat(json.at("/content/0/canEdit").asBoolean()).isTrue();
		then(queryCommentPageUseCase).should().queryReplyPage(101L, expectedQuery, "viewer-id");
	}

	@Test
	@DisplayName("답글은 파라미터가 없으면 0페이지·10개·작성일 오름차순을 적용한다")
	void repliesBindEndpointDefaults() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 7, 11, 10);
		PageQuery expectedQuery = PageQuery.of(0, 10, List.of(SortQuery.asc("createdAt")));
		given(queryCommentPageUseCase.queryReplyPage(101L, expectedQuery, "viewer-id"))
				.willReturn(new PageResult<>(List.of(
						new CommentListItemResult(203L, 41L, "writer-3", "셋째", "/avatar-3", "기본 정렬 답글", 101L,
								createdAt, createdAt, 0, true, false, false)), 0, 10, 1));

		JsonNode json = performWithFreshPageModule(get("/api/comments/101/replies")
				.with(user(new UserPrincipal("viewer-id", "viewer"))));

		assertPage(json, 0, 10, 1, 1, 1, true, true, false, true);
		assertThat(json.at("/content/0/id").asLong()).isEqualTo(203L);
		assertThat(json.at("/content/0/content").asText()).isEqualTo("기본 정렬 답글");
		then(queryCommentPageUseCase).should().queryReplyPage(101L, expectedQuery, "viewer-id");
	}

	@Test
	@DisplayName("친구 목록은 기본 식별자 내림차순과 프로필 값을 반환한다")
	void friendsPreserveIdentifiersAndProfileValues() throws Exception {
		PageQuery expectedQuery = PageQuery.of(0, 10, List.of(SortQuery.desc("userId1")));
		given(queryFriendPageUseCase.queryFriendPage(expectedQuery, "user-1"))
				.willReturn(new PageResult<>(List.of(
						new FriendSummaryResult("friend-b", "비", "https://cdn.example/b.png"),
						new FriendSummaryResult("friend-a", "에이", null)), 0, 10, 2));

		JsonNode json = performWithFreshPageModule(get("/api/relation")
				.with(user(new UserPrincipal("user-1", "user"))));

		assertPage(json, 0, 10, 2, 1, 2, true, true, false, true);
		assertThat(json.at("/content/0/userId").asText()).isEqualTo("friend-b");
		assertThat(json.at("/content/0/nickname").asText()).isEqualTo("비");
		assertThat(json.at("/content/0/avatar").asText()).isEqualTo("https://cdn.example/b.png");
		assertThat(json.at("/content/1/userId").asText()).isEqualTo("friend-a");
		assertThat(json.at("/content/1/avatar").isNull()).isTrue();
		then(queryFriendPageUseCase).should().queryFriendPage(expectedQuery, "user-1");
	}

	@Test
	@DisplayName("받은 요청은 정렬 없는 기본 페이지 계약을 반환한다")
	void receivedRequestsRemainUnsorted() throws Exception {
		PageQuery expectedQuery = PageQuery.of(0, 10);
		given(queryFriendPageUseCase.queryReceivedFriendRequestPage(expectedQuery, "user-1"))
				.willReturn(new PageResult<>(List.of(
						new FriendSummaryResult("sender-2", "보낸이 둘", "/sender-2.png"),
						new FriendSummaryResult("sender-1", "보낸이 하나", "/sender-1.png")), 0, 10, 2));

		JsonNode json = performWithFreshPageModule(get("/api/relation/requests")
				.with(user(new UserPrincipal("user-1", "user"))));

		assertPage(json, 0, 10, 2, 1, 2, true, true, true, false);
		assertThat(json.at("/content/0/userId").asText()).isEqualTo("sender-2");
		assertThat(json.at("/content/1/userId").asText()).isEqualTo("sender-1");
		then(queryFriendPageUseCase).should().queryReceivedFriendRequestPage(expectedQuery, "user-1");
	}

	@Test
	@DisplayName("검색은 20개 기본 크기와 아바타 URL 변환 및 항목 순서를 보존한다")
	void searchPreservesDefaultSizeAvatarMappingAndOrder() throws Exception {
		PageQuery expectedQuery = PageQuery.of(0, 20);
		given(searchUserUseCase.searchUsers("테스트", expectedQuery)).willReturn(new PageResult<>(List.of(
				new UserSearchResult("user-2", "둘째", new UserAvatarReference("avatars/two.png", false, true)),
				new UserSearchResult("user-1", "첫째", new UserAvatarReference("https://cdn.example/one.png", true, true))),
				0, 20, 2));
		JsonNode json = performWithFreshPageModule(get("/api/search").param("keyword", "테스트"));

		assertPage(json, 0, 20, 2, 1, 2, true, true, true, false);
		assertThat(json.at("/content/0/userId").asText()).isEqualTo("user-2");
		assertThat(json.at("/content/0/avatar").asText()).isEqualTo("http://localhost:9000/piku/avatars/two.png");
		assertThat(json.at("/content/1/userId").asText()).isEqualTo("user-1");
		assertThat(json.at("/content/1/avatar").asText()).isEqualTo("https://cdn.example/one.png");
		then(searchUserUseCase).should().searchUsers("테스트", expectedQuery);
	}

	@Test
	@DisplayName("검색은 명시한 페이지·크기·정렬과 키워드를 그대로 적용한다")
	void searchBindsExplicitPaginationSortAndKeyword() throws Exception {
		PageQuery expectedQuery = PageQuery.of(2, 7, List.of(SortQuery.asc("nickname")));
		given(searchUserUseCase.searchUsers("명시 검색", expectedQuery)).willReturn(new PageResult<>(List.of(
				new UserSearchResult("user-14", "가나다", new UserAvatarReference("https://cdn.example/14.png", true, true)),
				new UserSearchResult("user-15", "라마바", new UserAvatarReference("https://cdn.example/15.png", true, true))),
				2, 7, 30));

		JsonNode json = performWithFreshPageModule(get("/api/search")
				.param("keyword", "명시 검색")
				.param("page", "2")
				.param("size", "7")
				.param("sort", "nickname,asc"));

		assertPage(json, 2, 7, 30, 5, 2, false, false, false, true);
		assertThat(json.at("/content/0/userId").asText()).isEqualTo("user-14");
		assertThat(json.at("/content/0/nickname").asText()).isEqualTo("가나다");
		assertThat(json.at("/content/1/userId").asText()).isEqualTo("user-15");
		then(searchUserUseCase).should().searchUsers("명시 검색", expectedQuery);
	}

	private JsonNode performWithFreshPageModule(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		ObjectMapper mapper = originalObjectMapper.copy()
				.disable(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS)
				.registerModule(new PageModule(new SpringDataWebSettings(PageSerializationMode.DIRECT)));
		jsonConverter.setObjectMapper(mapper);
		ListAppender<ILoggingEvent> appender = attachRootAppender();

		try {
			MvcResult result = mockMvc.perform(request)
					.andExpect(status().isOk())
					.andReturn();
			assertThat(warnings(appender)).isEmpty();

			mapper.writeValueAsString(new PageImpl<>(List.of("control"), PageRequest.of(0, 1), 1));
			assertThat(warnings(appender)).isNotEmpty();
			return mapper.readTree(result.getResponse().getContentAsString());
		} finally {
			detachRootAppender(appender);
		}
	}

	private void assertPage(JsonNode json, int number, int size, long totalElements, int totalPages,
			int numberOfElements, boolean first, boolean last, boolean sortEmpty, boolean sortSorted) {
		assertThat(fieldNames(json)).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_FIELDS);
		assertThat(fieldNames(json.path("pageable"))).containsExactlyInAnyOrderElementsOf(PAGEABLE_FIELDS);
		assertThat(fieldNames(json.path("pageable").path("sort"))).containsExactlyInAnyOrderElementsOf(SORT_FIELDS);
		assertThat(fieldNames(json.path("sort"))).containsExactlyInAnyOrderElementsOf(SORT_FIELDS);
		assertThat(json.path("number").asInt()).isEqualTo(number);
		assertThat(json.path("size").asInt()).isEqualTo(size);
		assertThat(json.path("totalElements").asLong()).isEqualTo(totalElements);
		assertThat(json.path("totalPages").asInt()).isEqualTo(totalPages);
		assertThat(json.path("numberOfElements").asInt()).isEqualTo(numberOfElements);
		assertThat(json.path("first").asBoolean()).isEqualTo(first);
		assertThat(json.path("last").asBoolean()).isEqualTo(last);
		assertThat(json.path("empty").asBoolean()).isEqualTo(numberOfElements == 0);
		assertThat(json.at("/pageable/pageNumber").asInt()).isEqualTo(number);
		assertThat(json.at("/pageable/pageSize").asInt()).isEqualTo(size);
		assertThat(json.at("/pageable/offset").asLong()).isEqualTo((long) number * size);
		assertThat(json.at("/pageable/paged").asBoolean()).isTrue();
		assertThat(json.at("/pageable/unpaged").asBoolean()).isFalse();
		assertSort(json.path("sort"), sortEmpty, sortSorted);
		assertSort(json.at("/pageable/sort"), sortEmpty, sortSorted);
	}

	private void assertSort(JsonNode sort, boolean empty, boolean sorted) {
		assertThat(sort.path("empty").asBoolean()).isEqualTo(empty);
		assertThat(sort.path("sorted").asBoolean()).isEqualTo(sorted);
		assertThat(sort.path("unsorted").asBoolean()).isEqualTo(!sorted);
	}

	private Set<String> fieldNames(JsonNode node) {
		return node.properties().stream().map(java.util.Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet());
	}

	private ListAppender<ILoggingEvent> attachRootAppender() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		return appender;
	}

	private void detachRootAppender(ListAppender<ILoggingEvent> appender) {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
		appender.stop();
	}

	private List<String> warnings(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(message -> message.contains(PAGE_WARNING))
				.toList();
	}
}
