package com.pikume.back.user.auth.application.port.in;

import com.pikume.back.user.auth.application.dto.*;

public interface QuerySignupAgreementUseCase {
    java.util.List<SignupAgreementDocument> querySignupAgreements();
}
