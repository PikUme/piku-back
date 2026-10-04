package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.domain.User;

public interface SignUpTransactionPort {

	User register(User user);
}
