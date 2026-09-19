package com.pikume.back.social.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import com.pikume.back.social.application.dto.FriendRemovalResult;
import com.pikume.back.social.application.dto.FriendRequestResult;
import com.pikume.back.social.application.dto.FriendSummaryResult;
import com.pikume.back.social.adapter.in.web.dto.*;
import com.pikume.back.social.application.port.in.CancelFriendRequestUseCase;
import com.pikume.back.social.application.port.in.QueryFriendPageUseCase;
import com.pikume.back.social.application.port.in.RejectFriendRequestUseCase;
import com.pikume.back.social.application.port.in.RemoveFriendshipUseCase;
import com.pikume.back.social.application.port.in.SendFriendRequestUseCase;

@Tag(name = "Friend", description = "친구 관련 API")
@RestController
@RequestMapping("/api/relation")
@RequiredArgsConstructor
@Slf4j
public class FriendController {

	private final SendFriendRequestUseCase sendFriendRequestUseCase;
	private final RejectFriendRequestUseCase rejectFriendRequestUseCase;
	private final CancelFriendRequestUseCase cancelFriendRequestUseCase;
	private final RemoveFriendshipUseCase removeFriendshipUseCase;
	private final QueryFriendPageUseCase queryFriendPageUseCase;

	@Operation(summary = "친구 요청,수락", description = "사용자가 다른 사용자에게 친구 요청을 보내거나, 이미 요청이 있을 경우 수락합니다.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "친구 요청 성공 혹은 수락", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FriendRequestResponseDto.class), examples = {
					@ExampleObject(name = "요청 보냄", value = "{\"accepted\": false, \"message\": \"친구 요청을 보냈습니다.\"}"),
					@ExampleObject(name = "요청 수락", value = "{\"accepted\": true, \"message\": \"친구 요청을 수락했습니다.\"}")
			})),
			@ApiResponse(responseCode = "409", description = "이미 친구이거나 같은 방향의 친구 요청이 대기 중인 상태", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = {
					@ExampleObject(name = "이미 친구", value = "{\"type\":\"https://api.pikume.com/problems/social/already-friends\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"이미 친구입니다.\",\"instance\":\"/api/relation\"}"),
					@ExampleObject(name = "중복 친구 요청", value = "{\"type\":\"https://api.pikume.com/problems/social/duplicate-friend-request\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"이미 친구 요청을 보냈습니다.\",\"instance\":\"/api/relation\"}")
			})),
			@ApiResponse(responseCode = "400", description = "잘못된 요청 (자신에게 요청 등)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/social/invalid-friend-request\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"자신에게 요청 할 수 없습니다.\",\"instance\":\"/api/relation\"}")))
	})
	@PostMapping
	public ResponseEntity<FriendRequestResponseDto> sendFriendRequest(
			@AuthenticationPrincipal UserPrincipal userPrincipal,
			@RequestBody FriendRequestDto requestDto) {
		log.debug("event=friend_request_requested outcome=accepted userId={} targetUserId={}",
				userPrincipal.getId(), requestDto.getToUserId());
		FriendRequestResult response = sendFriendRequestUseCase.sendFriendRequest(userPrincipal.getId(),
				requestDto.getToUserId());
		return ResponseEntity.ok(toResponseDto(response));
	}

	@Operation(summary = "친구 목록 조회", description = "친구들의 id,닉네임,아바타(프로필)을 반환합니다.")
	@ApiResponse(responseCode = "200", description = "친구 목록 반환")
	@GetMapping
	public ResponseEntity<OffsetPageResponse<FriendsDTO>> findFriendList(
			@ParameterObject @PageableDefault(sort = "userId1", direction = Sort.Direction.DESC) Pageable pageable,
			@AuthenticationPrincipal UserPrincipal userPrincipal) {
		log.info("{} 의 친구 목록 조회 요청", userPrincipal.getId());

		PageQuery pageQuery = SpringPageMapper.toPageQuery(pageable);
		PageResult<FriendsDTO> friendResults = queryFriendPageUseCase.queryFriendPage(pageQuery, userPrincipal.getId())
				.map(this::toFriendsDto);
		OffsetPageResponse<FriendsDTO> friends = OffsetPageResponseMapper.toResponse(friendResults, pageable);

		return ResponseEntity.ok(friends);
	}

	@Operation(summary = "받은 요청 목록 조회", description = "나에게 온 친구 요청 목록을 조회합니다.", responses = {
			@ApiResponse(responseCode = "200", description = "친구 요청 목록 조회 성공"),
			@ApiResponse(responseCode = "401", description = "인증되지 않은 사용자", content = @Content)
	})
	@GetMapping("/requests")
	public ResponseEntity<OffsetPageResponse<FriendsDTO>> findFriendRequests(
			@ParameterObject @PageableDefault Pageable pageable,
			@AuthenticationPrincipal UserPrincipal userPrincipal) {
		log.info("{} 의 받은 친구 요청 목록 조회", userPrincipal.getId());

		PageQuery pageQuery = SpringPageMapper.toPageQuery(pageable);
		PageResult<FriendsDTO> requestResults = queryFriendPageUseCase
				.queryReceivedFriendRequestPage(pageQuery, userPrincipal.getId())
				.map(this::toFriendsDto);
		OffsetPageResponse<FriendsDTO> requests = OffsetPageResponseMapper.toResponse(requestResults, pageable);

		return ResponseEntity.ok(requests);
	}

	@Operation(summary = "친구 요청 거절", description = "받은 친구 요청을 거절합니다.", responses = {
			@ApiResponse(responseCode = "200", description = "친구 요청 거절 성공", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"message\": \"친구 요청을 거절했습니다.\", \"accepted\": false}"), schema = @Schema(implementation = FriendRequestResponseDto.class))),
			@ApiResponse(responseCode = "404", description = "친구 요청이 존재하지 않음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/social/friend-request-not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"해당 친구 요청 기록을 찾을 수 없습니다.\",\"instance\":\"/api/relation/requests/{fromUserId}\"}")))
	})
	@DeleteMapping("/requests/{fromUserId}")
	public ResponseEntity<FriendRequestResponseDto> rejectFriendRequest(
			@AuthenticationPrincipal UserPrincipal userPrincipal,
			@PathVariable String fromUserId) {
		log.info("{} 가 {} 의 친구 요청 거절", userPrincipal.getId(), fromUserId);
		FriendRequestResult response = rejectFriendRequestUseCase.rejectFriendRequest(userPrincipal.getId(), fromUserId);
		return ResponseEntity.ok(toResponseDto(response));
	}

	@Operation(summary = "친구 요청 취소", description = "친구 요청을 취소합니다.", responses = {
			@ApiResponse(responseCode = "200", description = "친구 요청 취소 성공", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"message\": \"친구 요청을 취소했습니다.\", \"accepted\": false}"), schema = @Schema(implementation = FriendRequestResponseDto.class))),
			@ApiResponse(responseCode = "404", description = "취소할 친구 요청이 존재하지 않음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/social/friend-request-not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"요청 보낸 기록이 없습니다.\",\"instance\":\"/api/relation/cancel/{toUserId}\"}")))
	})
	@DeleteMapping("/cancel/{toUserId}")
	public ResponseEntity<FriendRequestResponseDto> cancelFriendRequest(
			@AuthenticationPrincipal UserPrincipal userPrincipal,
			@PathVariable String toUserId) {
		log.info("{} 가 {} 에게 보낸 친구 요청 취소", userPrincipal.getId(), toUserId);
		FriendRequestResult response = cancelFriendRequestUseCase.cancelFriendRequest(userPrincipal.getId(), toUserId);
		return ResponseEntity.ok(toResponseDto(response));
	}

	@Operation(summary = "친구 끊기", description = "특정 사용자의 친구 관계를 삭제합니다.", responses = {
			@ApiResponse(responseCode = "200", description = "친구 관계 삭제 성공"),
			@ApiResponse(responseCode = "404", description = "친구 관계가 존재하지 않을 경우", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/social/friend-not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"친구 관계가 존재하지 않습니다.\",\"instance\":\"/api/relation/{toUserId}\"}")))
	})
	@DeleteMapping("/{toUserId}")
	public ResponseEntity<FriendRemoveDTO> removeFriend(
			@AuthenticationPrincipal UserPrincipal userPrincipal,
			@PathVariable String toUserId) {

		String fromUserId = userPrincipal.getId();
		log.info("User {} is unfriending user {}", fromUserId, toUserId);
		FriendRemovalResult response = removeFriendshipUseCase.removeFriend(fromUserId, toUserId);
		return ResponseEntity.ok(toRemoveDto(response));
	}

	private FriendRequestResponseDto toResponseDto(FriendRequestResult result) {
		return new FriendRequestResponseDto(result.accepted(), result.message());
	}

	private FriendsDTO toFriendsDto(FriendSummaryResult result) {
		return new FriendsDTO(result.userId(), result.nickname(), result.avatar());
	}

	private FriendRemoveDTO toRemoveDto(FriendRemovalResult result) {
		return new FriendRemoveDTO(result.success(), result.message());
	}
}
