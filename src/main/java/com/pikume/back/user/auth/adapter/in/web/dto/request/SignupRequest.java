package com.pikume.back.user.auth.adapter.in.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class SignupRequest {

	@NotBlank(message = "이메일은 필수 값입니다.")
	private String email;

	@NotBlank(message = "비밀번호는 필수 값입니다.")
	private String password;

	private String nickname;

	@NotNull(message = "캐릭터 선택은 필수입니다.")
	private Long fixedCharacterId;

	@NotBlank(message = "이메일 인증은 필수입니다.")
	private String emailVerificationToken;
}
