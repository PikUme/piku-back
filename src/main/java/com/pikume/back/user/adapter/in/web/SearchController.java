package com.pikume.back.user.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.pikume.back.global.pagination.PageQuery;
import com.pikume.back.global.pagination.PageResult;
import com.pikume.back.global.pagination.SpringPageMapper;
import com.pikume.back.global.adapter.in.web.pagination.OffsetPageResponse;
import com.pikume.back.global.adapter.in.web.pagination.OffsetPageResponseMapper;
import com.pikume.back.global.port.out.ResolveObjectUrlPort;
import com.pikume.back.user.adapter.in.web.dto.response.UserSearchResponse;
import com.pikume.back.user.application.port.in.SearchUserUseCase;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
@Tag(name = "Users", description = "유저 관련 API")
public class SearchController {

	private final SearchUserUseCase searchUserUseCase;
	private final ResolveObjectUrlPort resolveObjectUrlPort;

	@Operation(summary = "사용자 검색", description = "키워드로 사용자를 검색합니다.")
	@GetMapping
	public ResponseEntity<OffsetPageResponse<UserSearchResponse>> searchUsers(
			@RequestParam String keyword,
			@PageableDefault(size = 20) Pageable pageable) {
		PageQuery pageQuery = SpringPageMapper.toPageQuery(pageable);
		PageResult<UserSearchResponse> searchResults = searchUserUseCase.searchUsers(keyword, pageQuery)
				.map(result -> UserSearchResponse.from(result, resolveObjectUrlPort));
		OffsetPageResponse<UserSearchResponse> responsePage = OffsetPageResponseMapper.toResponse(searchResults, pageable);
		return ResponseEntity.ok(responsePage);
	}
}
