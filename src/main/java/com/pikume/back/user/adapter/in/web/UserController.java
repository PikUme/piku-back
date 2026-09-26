package com.pikume.back.user.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.error.CommonProblemType;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.port.out.ResolveObjectUrlPort;
import com.pikume.back.user.adapter.in.web.dto.request.UpdateProfileRequest;
import com.pikume.back.user.adapter.in.web.dto.response.NicknameChangeResponse;
import com.pikume.back.user.adapter.in.web.dto.response.NicknameCheckResponse;
import com.pikume.back.user.adapter.in.web.dto.response.ProfilePreviewResponse;
import com.pikume.back.user.adapter.in.web.dto.response.UserProfileResponse;
import com.pikume.back.user.application.dto.ProfilePreviewResult;
import com.pikume.back.user.application.dto.UpdateProfileCommand;
import com.pikume.back.user.application.dto.UpdateProfileFailureReason;
import com.pikume.back.user.application.dto.UpdateProfileResult;
import com.pikume.back.user.application.exception.ProfileImageNotFoundException;
import com.pikume.back.user.application.dto.UserProfileResult;
import com.pikume.back.user.application.port.in.ReserveNicknameUseCase;
import com.pikume.back.user.application.port.in.QueryUserProfileUseCase;
import com.pikume.back.user.application.port.in.UpdateUserProfileUseCase;
import com.pikume.back.user.adapter.in.web.problem.UserProblemType;

@Tag(name = "Users", description = "유저 관련 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

	private final QueryUserProfileUseCase queryUserProfileUseCase;
	private final UpdateUserProfileUseCase updateUserProfileUseCase;
	private final ReserveNicknameUseCase reserveNicknameUseCase;
	private final ProblemDetailFactory problemDetailFactory;
	private final ResolveObjectUrlPort resolveObjectUrlPort;

	@Operation(summary = "프로필 미리보기 정보 반환", description = "사용자의 프로필 미리보기 시 사용될 정보를 조회하여 반환합니다.")
	@GetMapping("/{userId}/profile-preview")
	public ResponseEntity<ProfilePreviewResponse> queryProfilePreview(
			@PathVariable String userId,
			@AuthenticationPrincipal UserPrincipal userDetails) {

		String loginUserId = userDetails != null ? userDetails.getId() : null;
		ProfilePreviewResult result = queryUserProfileUseCase.queryProfilePreview(userId, loginUserId);

		return ResponseEntity.ok(ProfilePreviewResponse.from(result, resolveObjectUrlPort));
	}

	@Operation(summary = "사용자 프로필 조회")
	@GetMapping("/{userId}")
	public ResponseEntity<UserProfileResponse> queryUserProfile(
			@PathVariable String userId,
			@AuthenticationPrincipal UserPrincipal userDetails) {
		UserProfileResult result = queryUserProfileUseCase.queryUserProfile(userId, userDetails.getId());

		return ResponseEntity.ok(UserProfileResponse.from(result, resolveObjectUrlPort));
	}

	@Operation(summary = "닉네임 중복조회 검사", responses = {
			@ApiResponse(responseCode = "200", description = "사용 가능한 닉네임입니다."),
			@ApiResponse(responseCode = "409", description = "이미 사용 중인 닉네임입니다.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/user/nickname-conflict\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"이미 사용 중인 닉네임입니다.\",\"instance\":\"/api/users/nickname/availability\"}")))
	})
	@GetMapping("/nickname/availability")
	public ResponseEntity<?> checkNickname(
			@RequestParam String nickname,
			@AuthenticationPrincipal UserPrincipal userDetails) {
		boolean reserved = reserveNicknameUseCase.reserveIfAvailable(nickname, userDetails.getId());
		if (reserved) {
			NicknameCheckResponse response = new NicknameCheckResponse(true, "사용 가능한 닉네임입니다.");
			return ResponseEntity.ok(response);
		}

		ProblemDetail problemDetail = problemDetailFactory.create(
				UserProblemType.NICKNAME_CONFLICT,
				"이미 사용 중인 닉네임입니다.",
				"/api/users/nickname/availability");
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
	}

	@Operation(summary = "변경할 닉네임/캐릭터 사진 등록")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "닉네임 변경 성공"),
			@ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/validation/invalid-request\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"변경할 닉네임이나 캐릭터 정보가 없습니다.\",\"instance\":\"/api/users/profile\"}"))),
			@ApiResponse(responseCode = "404", description = "참조 리소스를 찾을 수 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = @ExampleObject(value = "{\"type\":\"https://api.pikume.com/problems/common/resource-not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"존재하지 않는 캐릭터입니다.\",\"instance\":\"/api/users/profile\"}"))),
			@ApiResponse(responseCode = "409", description = "닉네임 또는 프로필 변경 충돌", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class), examples = {
					@ExampleObject(name = "nicknameConflict", value = "{\"type\":\"https://api.pikume.com/problems/user/nickname-conflict\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"이미 사용 중인 닉네임입니다.\",\"instance\":\"/api/users/profile\"}"),
					@ExampleObject(name = "profileConflict", value = "{\"type\":\"https://api.pikume.com/problems/user/profile-conflict\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"점유 정보가 없거나 만료되었거나 본인이 아닙니다.\",\"instance\":\"/api/users/profile\"}")
			}))
	})
	@PatchMapping("/profile")
	public ResponseEntity<?> changeNickname(
			@AuthenticationPrincipal UserPrincipal userDetails,
			@RequestBody UpdateProfileRequest updateProfileRequest) {
		UpdateProfileCommand command = new UpdateProfileCommand(
				userDetails.getId(),
				updateProfileRequest.newNickname(),
				updateProfileRequest.characterId());
		UpdateProfileResult result = updateUserProfileUseCase.updateProfile(command);
		if (result.success()) {
			return ResponseEntity.ok(NicknameChangeResponse.from(result, resolveObjectUrlPort));
		}

		return buildUpdateProfileFailureResponse(result);
	}

	@PutMapping("/profile-image")
	public ResponseEntity<?> updateProfileImage(
			@AuthenticationPrincipal UserPrincipal userPrincipal,
			@RequestParam Long imageId) {
		updateUserProfileUseCase.updateProfileImage(userPrincipal.getId(), imageId);
		return ResponseEntity.ok().build();
	}

	private ResponseEntity<ProblemDetail> buildUpdateProfileFailureResponse(UpdateProfileResult result) {
		UpdateProfileFailureReason failureReason = result.failureReason();
		if (failureReason == null) {
			ProblemDetail problemDetail = problemDetailFactory.create(
					CommonProblemType.INTERNAL_SERVER_ERROR,
					"프로필 변경 결과를 해석할 수 없습니다.",
					"/api/users/profile");
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
		}

		return switch (failureReason) {
			case INVALID_REQUEST -> ResponseEntity.badRequest().body(problemDetailFactory.create(
					com.pikume.back.global.error.ValidationProblemType.INVALID_REQUEST,
					result.message(),
					"/api/users/profile"));
			case NICKNAME_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetailFactory.create(
					UserProblemType.NICKNAME_CONFLICT,
					result.message(),
					"/api/users/profile"));
			case PROFILE_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetailFactory.create(
					UserProblemType.PROFILE_CONFLICT,
					result.message(),
					"/api/users/profile"));
			case RESOURCE_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetailFactory.create(
					CommonProblemType.RESOURCE_NOT_FOUND,
					result.message(),
					"/api/users/profile"));
		};
	}
}
