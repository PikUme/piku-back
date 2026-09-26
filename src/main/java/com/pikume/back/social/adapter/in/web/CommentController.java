package com.pikume.back.social.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.pagination.PageQuery;
import com.pikume.back.global.pagination.PageResult;
import com.pikume.back.global.pagination.SpringPageMapper;
import com.pikume.back.global.adapter.in.web.pagination.OffsetPageResponse;
import com.pikume.back.global.adapter.in.web.pagination.OffsetPageResponseMapper;
import com.pikume.back.social.adapter.in.web.dto.CommentDeleteResponseDto;
import com.pikume.back.social.application.dto.CommentDeleteResult;
import com.pikume.back.social.application.dto.CommentListItemResult;
import com.pikume.back.social.application.dto.CommentResult;
import com.pikume.back.social.adapter.in.web.dto.CommentListResponseDto;
import com.pikume.back.social.adapter.in.web.dto.CommentRequestDto;
import com.pikume.back.social.adapter.in.web.dto.CommentResponseDto;
import com.pikume.back.social.adapter.in.web.dto.CommentUpdateDto;
import com.pikume.back.social.application.port.in.CreateCommentUseCase;
import com.pikume.back.social.application.port.in.DeleteCommentUseCase;
import com.pikume.back.social.application.port.in.QueryCommentPageUseCase;
import com.pikume.back.social.application.port.in.UpdateCommentUseCase;

@Tag(name = "Comment", description = "댓글 관련 API")
@RestController
@Slf4j
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

	private final CreateCommentUseCase createCommentUseCase;
	private final UpdateCommentUseCase updateCommentUseCase;
	private final DeleteCommentUseCase deleteCommentUseCase;
	private final QueryCommentPageUseCase queryCommentPageUseCase;

	@Operation(summary = "댓글 작성", description = "댓글을 작성합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "댓글 작성 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 댓글 또는 답글 요청", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "401", description = "익명 답글 권한 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "일기 또는 댓글을 찾을 수 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	})
	@SecurityRequirement(name = "JWT")
	@PostMapping
	public ResponseEntity<CommentResponseDto> createComment(@RequestBody CommentRequestDto commentRequestDto,
			@AuthenticationPrincipal UserPrincipal userDetails) {
		log.info("사용자 {}님이 {} 일기, {} 댓글에 댓글 등록 요청, 댓글 내용: {}", userDetails.getId(), commentRequestDto.getDiaryId(),
				commentRequestDto.getParentId(), commentRequestDto.getContent());
		CommentResult isSaved = createCommentUseCase.createComment(
				commentRequestDto.getDiaryId(),
				commentRequestDto.getContent(),
				commentRequestDto.getParentId(),
				userDetails.getId());

		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(isSaved));
	}

	@Operation(summary = "댓글 수정", description = "댓글 내용을 수정합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "댓글 수정 성공"),
			@ApiResponse(responseCode = "400", description = "삭제된 댓글", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "401", description = "댓글 수정 권한 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "일기 또는 댓글을 찾을 수 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	})
	@PatchMapping("/{commentId}")
	public ResponseEntity<CommentResponseDto> updateComment(@PathVariable Long commentId,
			@RequestBody CommentUpdateDto updateDto, @AuthenticationPrincipal UserPrincipal userDetails) {
		log.info("사용자 {}님이 {} 댓글 수정 요청, 수정할 댓글 내용: {}", userDetails.getId(), commentId, updateDto.getContent());
		CommentResult isSaved = updateCommentUseCase.updateComment(commentId, updateDto.getContent(), userDetails.getId());

		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(isSaved));
	}

	@Operation(summary = "원댓글 조회", description = "특정 일기의 루트 댓글을 페이징하여 조회합니다. 각 댓글의 대댓글 개수 포함.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "루트 댓글 Page 조회 성공"),
			@ApiResponse(responseCode = "404", description = "조회 가능한 일기를 찾을 수 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	})
	@GetMapping
	public ResponseEntity<OffsetPageResponse<CommentListResponseDto>> getRootComments(
			@RequestParam Long diaryId,
			@ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC, size = 10) Pageable pageable,
			@AuthenticationPrincipal UserPrincipal userDetails) {
		log.info("일기 {}의 원댓글 조회 요청, page: {}, size: {}", diaryId, pageable.getPageNumber(), pageable.getPageSize());

		String viewerId = userDetails != null ? userDetails.getId() : null;
		PageQuery pageQuery = SpringPageMapper.toPageQuery(pageable);
		PageResult<CommentListResponseDto> rootCommentResults = queryCommentPageUseCase.queryRootCommentPage(diaryId, pageQuery,
				viewerId)
				.map(this::toResponse);
		OffsetPageResponse<CommentListResponseDto> rootCommentsPage = OffsetPageResponseMapper.toResponse(rootCommentResults,
				pageable);

		return ResponseEntity.status(HttpStatus.OK).body(rootCommentsPage);
	}

	@Operation(summary = "대댓글 조회", description = "특정 부모 댓글의 대댓글 목록을 페이징하여 조회합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "답글 Page 조회 성공"),
			@ApiResponse(responseCode = "404", description = "일기 또는 부모 댓글을 찾을 수 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	})
	@GetMapping("/{parentCommentId}/replies")
	public ResponseEntity<OffsetPageResponse<CommentListResponseDto>> getReplies(
			@PathVariable Long parentCommentId,
			@ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC, size = 10) Pageable pageable,
			@AuthenticationPrincipal UserPrincipal userDetails) {
		log.info("부모 댓글 {}에 대한 대댓글 조회 요청, page: {}, size: {}", parentCommentId, pageable.getPageNumber(),
				pageable.getPageSize());

		String viewerId = userDetails != null ? userDetails.getId() : null;
		PageQuery pageQuery = SpringPageMapper.toPageQuery(pageable);
		PageResult<CommentListResponseDto> replyResults = queryCommentPageUseCase.queryReplyPage(parentCommentId, pageQuery,
				viewerId)
				.map(this::toResponse);
		OffsetPageResponse<CommentListResponseDto> repliesPage = OffsetPageResponseMapper.toResponse(replyResults, pageable);

		return ResponseEntity.ok(repliesPage);
	}

	@Operation(summary = "댓글 삭제", description = "댓글을 삭제합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "댓글 삭제 성공"),
			@ApiResponse(responseCode = "400", description = "이미 삭제된 댓글", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "401", description = "댓글 삭제 권한 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
			@ApiResponse(responseCode = "404", description = "일기 또는 댓글을 찾을 수 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
	})
	@DeleteMapping("/{commentId}")
	public ResponseEntity<CommentDeleteResponseDto> deleteComment(@PathVariable Long commentId,
			@AuthenticationPrincipal UserPrincipal userDetails) {
		log.info("사용자 {}님이 {} 댓글 삭제 요청", userDetails.getId(), commentId);
		CommentDeleteResult isDeleted = deleteCommentUseCase.deleteComment(commentId, userDetails.getId());

		return ResponseEntity.status(HttpStatus.OK).body(toResponse(isDeleted));
	}

	private CommentResponseDto toResponse(CommentResult result) {
		return new CommentResponseDto(result.id(), result.content(), result.createdAt());
	}

	private CommentDeleteResponseDto toResponse(CommentDeleteResult result) {
		return new CommentDeleteResponseDto(result.success(), result.message(), result.commentId());
	}

	private CommentListResponseDto toResponse(CommentListItemResult result) {
		return new CommentListResponseDto(
				result.id(),
				result.diaryId(),
				result.userId(),
				result.nickname(),
				result.avatar(),
				result.content(),
				result.parentId(),
				result.createdAt(),
				result.updatedAt(),
				result.replyCount(),
				result.canReply(),
				result.canEdit(),
				result.canDelete());
	}
}
