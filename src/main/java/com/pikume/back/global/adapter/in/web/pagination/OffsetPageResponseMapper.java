package com.pikume.back.global.adapter.in.web.pagination;

import com.pikume.back.global.pagination.PageResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class OffsetPageResponseMapper {

	private OffsetPageResponseMapper() {
	}

	public static <T> OffsetPageResponse<T> toResponse(PageResult<T> pageResult, Pageable pageable) {
		long offset = (long) pageResult.page() * pageResult.size();
		long totalElements = adjustedTotalElements(pageResult, offset);
		int totalPages = totalPages(totalElements, pageResult.size());
		OffsetPageResponse.SortResponse sort = toSortResponse(pageable.getSort());
		OffsetPageResponse.PageableResponse pageableResponse = new OffsetPageResponse.PageableResponse(
				pageResult.page(),
				pageResult.size(),
				sort,
				offset,
				true,
				false);

		return new OffsetPageResponse<>(
				pageResult.content(),
				pageableResponse,
				(long) pageResult.page() + 1 >= totalPages,
				totalPages,
				totalElements,
				pageResult.size(),
				pageResult.page(),
				sort,
				pageResult.page() <= 0,
				pageResult.content().size(),
				pageResult.content().isEmpty());
	}

	private static <T> long adjustedTotalElements(PageResult<T> pageResult, long offset) {
		if (!pageResult.content().isEmpty() && offset + pageResult.size() > pageResult.totalElements()) {
			return offset + pageResult.content().size();
		}
		return pageResult.totalElements();
	}

	private static int totalPages(long totalElements, int size) {
		if (size <= 0) {
			return 0;
		}
		return (int) Math.ceil((double) totalElements / size);
	}

	private static OffsetPageResponse.SortResponse toSortResponse(Sort sort) {
		return new OffsetPageResponse.SortResponse(sort.isEmpty(), sort.isSorted(), sort.isUnsorted());
	}
}
