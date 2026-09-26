package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.domain.*;
import com.pikume.back.user.domain.User;
import java.time.Instant;
import java.util.Optional;
public interface SignupStorePort {
    Optional<SignupAuthentication> lockProof(String tokenHash);
    void saveProof(SignupAuthentication proof);
    Optional<Verification> lockChallenge(String challengeId);
    void saveChallenge(Verification challenge);
    Optional<User> findUserByEmail(String email);
    Optional<User> findUser(String userId);
    User createUser(User user);
    Optional<UserOAuthAccount> findAccount(String provider, String subject);
    Optional<UserOAuthAccount> findUserAccount(String userId, String provider);
    void createAccount(UserOAuthAccount account);
    void recordAgreement(UserAgreement agreement);
    /** Returns the reservation time sampled after acquiring the shared rate-limit lock. */
    Instant reserveEmailSend(String emailHash, String originHash, int emailLimit, int originLimit, int resendSeconds);
    void purgeExpired(Instant now);
}
