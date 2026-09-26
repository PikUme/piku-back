package com.pikume.back.user.auth.application.service;
import com.pikume.back.user.auth.application.port.in.CleanupOAuthRequestsUseCase;
import com.pikume.back.user.auth.application.port.out.OAuthAuthorizationRequestStorePort;
import com.pikume.back.user.auth.application.port.out.OAuthProtocolPolicyPort;
import org.springframework.stereotype.Service;
import java.time.Instant;
@Service
public class OAuthRequestCleanupService implements CleanupOAuthRequestsUseCase {
    private final OAuthAuthorizationRequestStorePort store;
    private final OAuthProtocolPolicyPort policy;
    public OAuthRequestCleanupService(OAuthAuthorizationRequestStorePort store, OAuthProtocolPolicyPort policy) {
        this.store = store; this.policy = policy;
    }
    public void cleanup() { store.cleanup(Instant.now(), policy.cleanupBatchSize()); }
}
