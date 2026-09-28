package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.domain.Verification;
import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationStorePort {
    Optional<Verification> lockVerification(String emailVerificationId);
    Optional<Verification> lockLatestVerification(String email);
    Optional<Verification> lockByTokenHash(String tokenHash);
    void saveVerification(Verification verification);
    Instant reserveEmailSend(String emailHash, String originHash, int emailLimit, int originLimit, int resendSeconds);
    void purgeExpired(Instant now);
}
