package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.port.out.OAuthAuthorizationRequestStorePort;
import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import static com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.*;
import static com.pikume.back.user.auth.domain.exception.OAuthRequestException.Reason.*;

@Repository
public class OAuthAuthorizationRequestPersistenceAdapter implements OAuthAuthorizationRequestStorePort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final RowMapper<OAuthAuthorizationRequest> ROW = (rs, row) -> new OAuthAuthorizationRequest(
            rs.getString("id"), rs.getString("state_hash"), rs.getString("caller_binding_hash"),
            rs.getString("encrypted_nonce"), rs.getString("encrypted_code_verifier"), rs.getString("device_id"),
            Purpose.valueOf(rs.getString("purpose")), rs.getString("target_user_id"), Channel.valueOf(rs.getString("channel")),
            rs.getString("registration"), Status.valueOf(rs.getString("status")), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    public OAuthAuthorizationRequestPersistenceAdapter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }
    @Override public void create(OAuthAuthorizationRequest request, String originHash, int callerLimit, int originLimit) {
        transaction.executeWithoutResult(tx -> {
            lockRateLimitGuard();
            reserve("origin:" + originHash, request.createdAt(), originLimit);
            reserve("caller:" + request.callerBindingHash(), request.createdAt(), callerLimit);
            jdbc.update("""
                    INSERT INTO oauth_authorization_requests
                    (id,state_hash,caller_binding_hash,encrypted_nonce,encrypted_code_verifier,device_id,purpose,
                     target_user_id,channel,registration,status,created_at,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, request.id(), request.stateHash(), request.callerBindingHash(), request.encryptedNonce(),
                    request.encryptedCodeVerifier(), request.deviceId(), request.purpose().name(), request.targetUserId(),
                    request.channel().name(), request.registration(), request.status().name(),
                    utc(request.createdAt()), utc(request.expiresAt()));
        });
    }
    private void lockRateLimitGuard() {
        var guard = jdbc.queryForList("SELECT bucket_key FROM oauth_start_rate_limits WHERE bucket_key='guard' FOR UPDATE", String.class);
        if (guard.isEmpty()) throw new OAuthRequestException(CONFIGURATION);
    }
    private void reserve(String bucket, Instant now, int limit) {
        var counts = jdbc.query("SELECT request_count,window_started_at FROM oauth_start_rate_limits WHERE bucket_key=?",
                (rs, row) -> new Counter(rs.getInt(1), rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC)), bucket);
        if (counts.isEmpty()) {
            jdbc.update("INSERT INTO oauth_start_rate_limits(bucket_key,request_count,window_started_at) VALUES (?,1,?)", bucket, utc(now));
            return;
        }
        Counter current = counts.get(0);
        if (!current.startedAt().isAfter(now.minusSeconds(3600))) {
            jdbc.update("UPDATE oauth_start_rate_limits SET request_count=1,window_started_at=? WHERE bucket_key=?", utc(now), bucket);
        } else {
            if (current.count() >= limit) throw new OAuthRequestException(RATE_LIMITED);
            jdbc.update("UPDATE oauth_start_rate_limits SET request_count=request_count+1 WHERE bucket_key=?", bucket);
        }
    }
    @Override public OAuthAuthorizationRequest claim(String stateHash, String bindingHash, Channel channel, Clock clock) {
        return transaction.execute(tx -> {
            var matches = jdbc.query("SELECT * FROM oauth_authorization_requests WHERE state_hash=? FOR UPDATE", ROW, stateHash);
            if (matches.isEmpty()) throw new OAuthRequestException(NOT_FOUND);
            var request = matches.get(0);
            request.requireClaimable(bindingHash, channel, clock.instant());
            int changed = jdbc.update("UPDATE oauth_authorization_requests SET status='PROCESSING' WHERE id=? AND status='PENDING'", request.id());
            if (changed != 1) throw new OAuthRequestException(REPLAY);
            return request.withStatus(Status.PROCESSING);
        });
    }
    @Override public void finish(String id, Status status) {
        if (status != Status.CONSUMED && status != Status.FAILED) throw new OAuthRequestException(INVALID_REQUEST);
        transaction.executeWithoutResult(tx -> {
            if (jdbc.update("UPDATE oauth_authorization_requests SET status=? WHERE id=? AND status='PROCESSING'", status.name(), id) != 1)
                throw new OAuthRequestException(REPLAY);
        });
    }
    @Override public int cleanup(Instant now, int limit) {
        if (limit < 1 || limit > 10000) throw new OAuthRequestException(INVALID_REQUEST);
        return transaction.execute(tx -> {
            // The same mutex prevents cleanup from racing a counter reset.
            lockRateLimitGuard();
            int removed = jdbc.update("""
                    DELETE FROM oauth_authorization_requests
                    WHERE expires_at<=? AND (status<>'PROCESSING' OR expires_at<=?)
                    ORDER BY expires_at LIMIT ?
                    """, utc(now), utc(now.minusSeconds(3600)), limit);
            jdbc.update("DELETE FROM oauth_start_rate_limits WHERE bucket_key<>'guard' AND window_started_at<=? ORDER BY window_started_at LIMIT ?",
                    utc(now.minusSeconds(3600)), limit);
            return removed;
        });
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private record Counter(int count, Instant startedAt) {}
}
