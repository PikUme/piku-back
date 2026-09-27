package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.EmailSignupChallengeCommand;
import com.pikume.back.user.auth.application.dto.EmailSignupChallengeResult;

public interface SendSignupEmailCodeUseCase {
    EmailSignupChallengeResult sendEmailCode(EmailSignupChallengeCommand command);
}
