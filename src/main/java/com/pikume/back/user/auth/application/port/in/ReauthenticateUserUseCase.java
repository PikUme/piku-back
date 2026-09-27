package com.pikume.back.user.auth.application.port.in;

public interface ReauthenticateUserUseCase {
    void reauthenticate(String userId, String password);
}
