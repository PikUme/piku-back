package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.*;

public interface CleanupSignupUseCase {
    void purgeExpiredSignupArtifacts();
}
