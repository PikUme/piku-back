package com.pikume.back.social.adapter.in.web;

import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.adapter.in.web.pagination.OffsetPageResponse;
import com.pikume.back.social.adapter.in.web.dto.CommentListResponseDto;
import com.pikume.back.social.adapter.in.web.dto.FriendRequestDto;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Social OpenAPI schema")
class SocialOpenApiSchemaTest {

	@Test
	@DisplayName("익명 응답에서 빠질 수 있는 작성자 메타데이터는 nullable이다")
	void anonymousCommentMetadataIsNullable() {
		Map<String, Schema> schemas = ModelConverters.getInstance().readAll(CommentListResponseDto.class);
		Schema<?> response = schemas.get("CommentListResponseDto");

		assertThat(response.getProperties().get("userId").getNullable()).isTrue();
		assertThat(response.getProperties().get("nickname").getNullable()).isTrue();
		assertThat(response.getProperties().get("avatar").getNullable()).isTrue();
		assertThat(response.getProperties()).containsKeys("canReply", "canEdit", "canDelete");
	}

	@Test
	@DisplayName("offset page 응답은 최상위와 중첩 호환 필드를 문서화한다")
	void offsetPageSchemaDocumentsCompatibilityFields() {
		Map<String, Schema> schemas = ModelConverters.getInstance().readAll(OffsetPageResponse.class);
		Schema<?> response = schemas.get("OffsetPageResponse");
		Schema<?> pageable = schemas.get("PageableResponse");
		Schema<?> sort = schemas.get("SortResponse");

		assertThat(response.getProperties()).containsOnlyKeys(
				"content", "pageable", "last", "totalPages", "totalElements", "size",
				"number", "sort", "first", "numberOfElements", "empty");
		assertThat(pageable.getProperties()).containsOnlyKeys(
				"pageNumber", "pageSize", "sort", "offset", "paged", "unpaged");
		assertThat(sort.getProperties()).containsOnlyKeys("empty", "sorted", "unsorted");
	}

	@Test
	@DisplayName("친구 목록은 반환하지 않는 404와 204를 문서화하지 않는다")
	void friendPagesDoNotDocumentAbsentResponses() throws NoSuchMethodException {
		Method friendList = FriendController.class.getDeclaredMethod(
				"findFriendList", Pageable.class, UserPrincipal.class);
		Method requestList = FriendController.class.getDeclaredMethod(
				"findFriendRequests", Pageable.class, UserPrincipal.class);

		assertThat(responseCodes(friendList)).doesNotContain("404", "204");
		assertThat(responseCodes(requestList)).doesNotContain("204");
	}

	@Test
	@DisplayName("Social 오류 응답은 application/problem+json으로 문서화한다")
	void errorsUseProblemDetailsMediaType() throws NoSuchMethodException {
		Method addLike = LikeController.class.getDeclaredMethod("addLike", Long.class, UserPrincipal.class);
		Method deleteComment = CommentController.class.getDeclaredMethod(
				"deleteComment", Long.class, UserPrincipal.class);
		Method sendFriendRequest = FriendController.class.getDeclaredMethod(
				"sendFriendRequest", UserPrincipal.class, FriendRequestDto.class);

		assertProblemMediaType(addLike, "404");
		assertProblemMediaType(addLike, "409");
		assertProblemMediaType(deleteComment, "400");
		assertProblemMediaType(deleteComment, "401");
		assertProblemMediaType(deleteComment, "404");
		assertProblemMediaType(sendFriendRequest, "409");
	}

	@Test
	@DisplayName("친구 요청 409 응답은 이미 친구와 중복 요청을 별도 Problem Details 사례로 문서화한다")
	void friendRequestConflictDocumentsDistinctProblemDetailsExamples() throws NoSuchMethodException {
		Method sendFriendRequest = FriendController.class.getDeclaredMethod(
				"sendFriendRequest", UserPrincipal.class, FriendRequestDto.class);
		ApiResponse conflict = apiResponse(sendFriendRequest, "409");

		Map<String, String> examplesByName = Arrays.stream(conflict.content()[0].examples())
				.collect(java.util.stream.Collectors.toMap(ExampleObject::name, ExampleObject::value));

		assertThat(examplesByName.get("이미 친구"))
				.contains("https://api.pikume.com/problems/social/already-friends");
		assertThat(examplesByName.get("중복 친구 요청"))
				.contains("https://api.pikume.com/problems/social/duplicate-friend-request");
	}

	private String[] responseCodes(Method method) {
		Operation operation = method.getAnnotation(Operation.class);
		return java.util.stream.Stream.concat(
				Arrays.stream(method.getAnnotationsByType(ApiResponse.class)),
				operation == null ? java.util.stream.Stream.empty() : Arrays.stream(operation.responses()))
				.map(ApiResponse::responseCode)
				.toArray(String[]::new);
	}

	private void assertProblemMediaType(Method method, String responseCode) {
		ApiResponse response = apiResponse(method, responseCode);
		assertThat(response.content()).singleElement()
				.satisfies(content -> assertThat(content.mediaType())
						.isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
	}

	private ApiResponse apiResponse(Method method, String responseCode) {
		return Arrays.stream(method.getAnnotationsByType(ApiResponse.class))
				.filter(candidate -> candidate.responseCode().equals(responseCode))
				.findFirst()
				.orElseThrow();
	}
}
