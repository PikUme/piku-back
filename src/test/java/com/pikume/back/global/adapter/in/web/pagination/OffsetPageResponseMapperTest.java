package com.pikume.back.global.adapter.in.web.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pikume.back.global.pagination.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.SpringDataJacksonConfiguration.PageModule;
import org.springframework.data.web.config.SpringDataWebSettings;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OffsetPageResponseMapper")
class OffsetPageResponseMapperTest {

	@Nested
	@DisplayName("toResponse")
	class ToResponse {

		@Test
		@DisplayName("PageResult의 위치와 Pageable의 정렬로 모든 호환 필드를 만든다")
		void mapsAllCompatibilityFieldsFromTheirOwners() {
			PageResult<String> result = new PageResult<>(List.of("둘", "셋"), 2, 2, 9);
			Pageable pageable = PageRequest.of(7, 20, Sort.by(Sort.Order.desc("createdAt")));

			OffsetPageResponse<String> response = OffsetPageResponseMapper.toResponse(result, pageable);

			assertThat(response.content()).containsExactly("둘", "셋");
			assertThat(response.number()).isEqualTo(2);
			assertThat(response.size()).isEqualTo(2);
			assertThat(response.totalElements()).isEqualTo(9);
			assertThat(response.totalPages()).isEqualTo(5);
			assertThat(response.numberOfElements()).isEqualTo(2);
			assertThat(response.first()).isFalse();
			assertThat(response.last()).isFalse();
			assertThat(response.empty()).isFalse();
			assertThat(response.pageable().pageNumber()).isEqualTo(2);
			assertThat(response.pageable().pageSize()).isEqualTo(2);
			assertThat(response.pageable().offset()).isEqualTo(4L);
			assertThat(response.pageable().paged()).isTrue();
			assertThat(response.pageable().unpaged()).isFalse();
			assertThat(response.pageable().sort()).isEqualTo(new OffsetPageResponse.SortResponse(false, true, false));
			assertThat(response.sort()).isEqualTo(new OffsetPageResponse.SortResponse(false, true, false));
		}

		@Test
		@DisplayName("정렬이 없으면 두 sort 위치에 동일한 미정렬 상태를 둔다")
		void mapsUnsortedStateToBothLocations() {
			OffsetPageResponse<String> response = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of("하나"), 0, 10, 1),
					PageRequest.of(0, 10));

			assertThat(response.sort()).isEqualTo(new OffsetPageResponse.SortResponse(true, false, true));
			assertThat(response.pageable().sort()).isEqualTo(response.sort());
		}

		@Test
		@DisplayName("빈 첫 페이지와 범위를 벗어난 빈 페이지의 경계 값을 유지한다")
		void preservesEmptyPageBoundaries() {
			OffsetPageResponse<String> first = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of(), 0, 10, 0), PageRequest.of(0, 10));
			OffsetPageResponse<String> outOfRange = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of(), 5, 10, 12), PageRequest.of(5, 10));

			assertThat(first.totalPages()).isZero();
			assertThat(first.first()).isTrue();
			assertThat(first.last()).isTrue();
			assertThat(first.empty()).isTrue();
			assertThat(outOfRange.number()).isEqualTo(5);
			assertThat(outOfRange.totalElements()).isEqualTo(12);
			assertThat(outOfRange.totalPages()).isEqualTo(2);
			assertThat(outOfRange.first()).isFalse();
			assertThat(outOfRange.last()).isTrue();
			assertThat(outOfRange.empty()).isTrue();
		}

		@Test
		@DisplayName("마지막 구간에서 PageImpl과 같이 전체 개수를 위아래로 보정한다")
		void adjustsTotalInBothDirectionsOnNonEmptyLastRange() {
			OffsetPageResponse<String> upward = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of("셋", "넷"), 1, 2, 3), PageRequest.of(1, 2));
			OffsetPageResponse<String> downward = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of("다섯"), 1, 4, 7), PageRequest.of(1, 4));

			assertThat(upward.totalElements()).isEqualTo(4);
			assertThat(upward.totalPages()).isEqualTo(2);
			assertThat(upward.last()).isTrue();
			assertThat(downward.totalElements()).isEqualTo(5);
			assertThat(downward.totalPages()).isEqualTo(2);
			assertThat(downward.last()).isTrue();
		}

		@Test
		@DisplayName("큰 페이지 번호의 offset을 long으로 계산한다")
		void calculatesLargeOffsetAsLong() {
			OffsetPageResponse<String> response = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of(), 1_500_000_000, 2, 10), PageRequest.of(0, 1));

			assertThat(response.pageable().offset()).isEqualTo(3_000_000_000L);
		}

		@Test
		@DisplayName("최대 페이지 번호의 빈 결과를 마지막 페이지로 판단한다")
		void treatsMaximumOutOfRangePageAsLast() {
			OffsetPageResponse<String> response = OffsetPageResponseMapper.toResponse(
					new PageResult<>(List.of(), Integer.MAX_VALUE, 1, 10), PageRequest.of(0, 1));

			assertThat(response.pageable().offset()).isEqualTo(Integer.MAX_VALUE);
			assertThat(response.last()).isTrue();
		}

		@Test
		@DisplayName("항목의 순서와 null을 그대로 보존한다")
		void preservesItemOrderAndNulls() {
			List<TestItem> content = List.of(
					new TestItem("첫째", null),
					new TestItem("둘째", "표시"));

			OffsetPageResponse<TestItem> response = OffsetPageResponseMapper.toResponse(
					new PageResult<>(content, 0, 2, 2), PageRequest.of(0, 2));

			assertThat(response.content()).containsExactly(
					new TestItem("첫째", null),
					new TestItem("둘째", "표시"));
		}
	}

	@Test
	@DisplayName("기존 PageImpl JSON과 동일한 페이지 계약을 직렬화한다")
	void serializesTheSameContractAsPageImpl() {
		Pageable pageable = PageRequest.of(1, 2, Sort.by(Sort.Order.asc("nickname")));
		PageResult<String> result = new PageResult<>(List.of("가", "나"), 1, 2, 4);
		ObjectMapper mapper = directPageObjectMapper();

		JsonNode legacy = mapper.valueToTree(new PageImpl<>(result.content(), pageable, result.totalElements()));
		JsonNode response = mapper.valueToTree(OffsetPageResponseMapper.toResponse(result, pageable));

		assertThat(response).isEqualTo(legacy);
	}

	@Test
	@DisplayName("Spring Data PageModule이 등록되어도 새 응답은 Spring Page 타입에 의존하지 않는다")
	void serializesWithoutDependingOnSpringPageTypes() throws Exception {
		ObjectMapper responseMapper = directPageObjectMapper();
		OffsetPageResponse<String> response = OffsetPageResponseMapper.toResponse(
				new PageResult<>(List.of("항목"), 0, 10, 1), PageRequest.of(0, 10));

		String json = responseMapper.writeValueAsString(response);

		assertThat(json).contains("\"content\":[\"항목\"]", "\"totalElements\":1");
		assertThat(OffsetPageResponse.class.getDeclaredFields())
				.noneMatch(field -> field.getType().getName().startsWith("org.springframework.data"));
	}

	private ObjectMapper directPageObjectMapper() {
		return new ObjectMapper().registerModule(
				new PageModule(new SpringDataWebSettings(PageSerializationMode.DIRECT)));
	}

	private record TestItem(String name, String nullableValue) {
	}
}
