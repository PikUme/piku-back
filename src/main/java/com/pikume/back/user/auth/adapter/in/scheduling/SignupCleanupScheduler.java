package com.pikume.back.user.auth.adapter.in.scheduling;
import com.pikume.back.user.auth.application.port.in.CleanupSignupUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class SignupCleanupScheduler {
    private final CleanupSignupUseCase cleanup;
    @Scheduled(fixedDelayString="${signup.cleanup-interval-ms:3600000}",initialDelayString="${signup.cleanup-interval-ms:3600000}")
    public void purgeExpired() {cleanup.purgeExpiredSignupArtifacts();}
}
