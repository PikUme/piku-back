package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.LoginResult;

public interface IssueUserSessionUseCase {
    LoginResult issueSession(String userId, String deviceId);
}
