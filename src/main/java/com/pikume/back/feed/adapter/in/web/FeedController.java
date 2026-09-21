package com.pikume.back.feed.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.pikume.back.feed.adapter.in.web.dto.FeedCursorPageResponse;
import com.pikume.back.feed.adapter.in.web.dto.FeedDiaryResponse;
import com.pikume.back.feed.application.dto.FeedCursorPage;
import com.pikume.back.feed.application.dto.FeedCursorRequest;
import com.pikume.back.feed.application.dto.FeedDiaryResult;
import com.pikume.back.feed.application.dto.FeedSortMode;
import com.pikume.back.feed.application.port.in.QueryFeedDetailUseCase;
import com.pikume.back.feed.application.port.in.QueryFeedPageUseCase;
import com.pikume.back.feed.application.port.in.RecordFeedClickUseCase;
import com.pikume.back.security.principal.UserPrincipal;

@Tag(name = "Feed", description = "피드 관련 API")
@RestController
@Validated
@Slf4j
@RequestMapping("/api/diary")
@RequiredArgsConstructor
public class FeedController {

	private final QueryFeedDetailUseCase queryFeedDetailUseCase;
	private final QueryFeedPageUseCase queryFeedPageUseCase;
	private final RecordFeedClickUseCase recordFeedClickUseCase;
	private final FeedResponseMapper feedResponseMapper;
	private final FeedSortRequestMapper feedSortRequestMapper;

	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "일기 조회 성공",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FeedDiaryResponse.class))),
			@ApiResponse(responseCode = "401", description = "인증 실패",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "조회할 수 있는 일기를 찾을 수 없음",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "500", description = "서버 오류",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))
	})
	@Operation(summary = "일기 상세 조회", description = "특정 일기의 상세 정보를 조회합니다.")
	@GetMapping("/{diaryId}")
	public ResponseEntity<FeedDiaryResponse> getDiaryWithPhotos(@PathVariable Long diaryId,
			@AuthenticationPrincipal UserPrincipal userPrincipal) {
		log.debug("event=feed_detail_requested resourceId={}", diaryId);

		String viewerId = userPrincipal != null ? userPrincipal.getId() : null;
		FeedDiaryResult result = queryFeedDetailUseCase.queryDetail(diaryId, viewerId);
		if (viewerId != null) {
			recordFeedClickUseCase.recordClick(viewerId, diaryId);
		}
		return ResponseEntity.ok(feedResponseMapper.mapDiary(result));
	}

	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "일기 조회 성공",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = FeedCursorPageResponse.class))),
			@ApiResponse(responseCode = "400", description = "정렬 모드 또는 Cursor가 올바르지 않음",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "401", description = "인증 실패",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "500", description = "서버 오류",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
							schema = @Schema(implementation = ProblemDetail.class)))
	})
	@Operation(summary = "일기 피드 조회", description = """
			    cursor 기반으로 피드 목록을 조회합니다.
			    - cursor: 다음 페이지 조회용 opaque token
			    - limit: 1~100 사이 정수
			    - sort: recommended(추천순) 또는 latest(기록일 최신순)
			""")
	@GetMapping
	public ResponseEntity<FeedCursorPageResponse> getAllDiaries(
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			@Parameter(description = "피드 정렬 모드. 생략 시 recommended(추천순)이며, latest는 기록일 최신순입니다.",
					schema = @Schema(allowableValues = {"recommended", "latest"}, defaultValue = "recommended"))
			@RequestParam(required = false) String sort,
			@AuthenticationPrincipal UserPrincipal userPrincipal) {
		FeedSortMode sortMode = feedSortRequestMapper.map(sort);
		String viewerId = userPrincipal != null ? userPrincipal.getId() : null;
		FeedCursorPage<FeedDiaryResult> page = queryFeedPageUseCase.queryPage(
				new FeedCursorRequest(cursor, limit, sortMode),
				viewerId);
		return ResponseEntity.ok(feedResponseMapper.mapPage(page));
	}
}
