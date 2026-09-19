package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.vo.Nickname;

public interface CheckUserUniquenessPort {

	boolean isNicknameInUse(Nickname nickname);

	boolean isEmailRegistered(String email);
}
