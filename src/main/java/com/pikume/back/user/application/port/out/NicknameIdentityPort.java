package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.vo.Nickname;

public interface NicknameIdentityPort {

	String keyFor(Nickname nickname);
}
