package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.User;

import java.util.Optional;

public interface LoadUserForPasswordResetPort {

	/** 비밀번호 변경 트랜잭션이 끝날 때까지 회원을 잠가 프로필 완료·탈퇴와의 유실 갱신을 방지한다. */
	Optional<User> loadPasswordResetUser(String email);
}
