package com.pikume.back.social.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Social generated OpenAPI")
class SocialOpenApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("생성된 다섯 offset page 스키마는 호환 외형과 실제 항목 타입을 설명한다")
	void generatedPageSchemasReferenceConcreteItemTypes() throws Exception {
		String response = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		JsonNode document = objectMapper.readTree(response);

		assertPageItemReference(document, "/api/relation", "FriendsDTO");
		assertPageItemReference(document, "/api/relation/requests", "FriendsDTO");
		assertPageItemReference(document, "/api/comments", "CommentListResponseDto");
		assertPageItemReference(document, "/api/comments/{parentCommentId}/replies", "CommentListResponseDto");
		assertPageItemReference(document, "/api/search", "UserSearchResponse");
	}

	private void assertPageItemReference(JsonNode document, String path, String itemSchemaName) {
		JsonNode responseContent = document.path("paths")
				.path(path)
				.path("get")
				.path("responses")
				.path("200")
				.path("content");
		assertThat(responseContent.isObject() && !responseContent.isEmpty())
				.as("%s의 생성된 200 응답 content", path)
				.isTrue();
		JsonNode responseSchema = responseContent.elements().next().path("schema");
		JsonNode pageSchema = resolveReference(document, responseSchema);
		JsonNode contentSchema = resolveReference(document, pageSchema.path("properties").path("content"));
		JsonNode itemSchema = contentSchema.path("items");
		JsonNode pageableSchema = resolveReference(document, pageSchema.path("properties").path("pageable"));
		JsonNode pageableSortSchema = resolveReference(document, pageableSchema.path("properties").path("sort"));
		JsonNode sortSchema = resolveReference(document, pageSchema.path("properties").path("sort"));

		assertThat(fieldNames(pageSchema.path("properties")))
				.containsExactlyInAnyOrder(
						"content", "pageable", "last", "totalPages", "totalElements", "size",
						"number", "sort", "first", "numberOfElements", "empty");
		assertThat(fieldNames(pageableSchema.path("properties")))
				.containsExactlyInAnyOrder("pageNumber", "pageSize", "sort", "offset", "paged", "unpaged");
		assertThat(fieldNames(pageableSortSchema.path("properties")))
				.containsExactlyInAnyOrder("empty", "sorted", "unsorted");
		assertThat(fieldNames(sortSchema.path("properties")))
				.containsExactlyInAnyOrder("empty", "sorted", "unsorted");
		assertThat(itemSchema.path("$ref").asText())
				.as("%s의 생성된 Page content 항목 스키마", path)
				.endsWith("/" + itemSchemaName);
	}

	private Set<String> fieldNames(JsonNode properties) {
		return properties.properties().stream()
				.map(java.util.Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet());
	}

	private JsonNode resolveReference(JsonNode document, JsonNode schema) {
		if (!schema.hasNonNull("$ref")) {
			return schema;
		}
		String reference = schema.path("$ref").asText();
		String schemaName = reference.substring(reference.lastIndexOf('/') + 1);
		return document.path("components").path("schemas").path(schemaName);
	}
}
