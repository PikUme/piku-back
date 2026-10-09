package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.User;

import java.util.Optional;

public interface LoadUserForProfilePort {

	Optional<User> loadProfileUser(String userId);
}
