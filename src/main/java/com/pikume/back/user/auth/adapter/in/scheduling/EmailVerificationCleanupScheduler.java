package com.pikume.back.user.auth.adapter.in.scheduling;
import com.pikume.back.user.auth.application.port.in.EmailVerificationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class EmailVerificationCleanupScheduler {
    private final EmailVerificationUseCase verification;
    @Scheduled(fixedDelayString="${email-verification.cleanup-interval-ms:3600000}", initialDelayString="${email-verification.cleanup-interval-ms:3600000}")
    public void purgeExpired() { verification.purgeExpiredVerifications(); }
}
