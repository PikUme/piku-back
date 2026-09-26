package com.pikume.back.global.pagination;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

public final class SpringPageMapper {

	private SpringPageMapper() {
	}

	public static PageQuery toPageQuery(Pageable pageable) {
		List<SortQuery> sortOrders = pageable.getSort().stream()
				.map(order -> new SortQuery(
						order.getProperty(),
						order.isAscending() ? SortDirection.ASC : SortDirection.DESC))
				.toList();
		return new PageQuery(pageable.getPageNumber(), pageable.getPageSize(), sortOrders);
	}

	public static Pageable toPageable(PageQuery pageQuery) {
		Sort sort = Sort.unsorted();
		for (SortQuery order : pageQuery.sortOrders()) {
			Sort.Direction direction = order.direction() == SortDirection.ASC
					? Sort.Direction.ASC
					: Sort.Direction.DESC;
			sort = sort.and(Sort.by(direction, order.property()));
		}
		return PageRequest.of(pageQuery.page(), pageQuery.size(), sort);
	}

	public static <T> PageResult<T> toPageResult(Page<T> page) {
		return new PageResult<>(
				page.getContent(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements());
	}

}
