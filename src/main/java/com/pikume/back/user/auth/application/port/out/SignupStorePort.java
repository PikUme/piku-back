package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.domain.Verification;
import java.time.Instant;
import java.util.Optional;

public interface SignupStorePort {
    Optional<Verification> lockChallenge(String challengeId);
    void saveChallenge(Verification challenge);
    /** Returns the reservation time sampled after acquiring the shared rate-limit lock. */
    Instant reserveEmailSend(String emailHash, String originHash, int emailLimit, int originLimit, int resendSeconds);
    void purgeExpired(Instant now);
}
