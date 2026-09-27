package com.pikume.back.user.auth.adapter.in.scheduling;
import com.pikume.back.user.auth.application.port.in.CleanupOAuthRequestsUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class OAuthRequestCleanupScheduler {
    private final CleanupOAuthRequestsUseCase cleanup;
    public OAuthRequestCleanupScheduler(CleanupOAuthRequestsUseCase cleanup) { this.cleanup = cleanup; }
    @Scheduled(fixedDelayString = "${signup.protocol.cleanup-interval-ms:300000}", initialDelayString = "${signup.protocol.cleanup-interval-ms:300000}")
    public void clean() { cleanup.cleanup(); }
}
