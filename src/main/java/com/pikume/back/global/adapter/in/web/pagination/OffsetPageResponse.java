package com.pikume.back.global.adapter.in.web.pagination;

import java.util.List;

public record OffsetPageResponse<T>(
		List<T> content,
		PageableResponse pageable,
		boolean last,
		int totalPages,
		long totalElements,
		int size,
		int number,
		SortResponse sort,
		boolean first,
		int numberOfElements,
		boolean empty
) {

	public OffsetPageResponse {
		content = List.copyOf(content);
	}

	public record PageableResponse(
			int pageNumber,
			int pageSize,
			SortResponse sort,
			long offset,
			boolean paged,
			boolean unpaged
	) {
	}

	public record SortResponse(
			boolean empty,
			boolean sorted,
			boolean unsorted
	) {
	}
}
