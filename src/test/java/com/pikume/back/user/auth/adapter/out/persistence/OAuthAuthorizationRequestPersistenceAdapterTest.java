package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import static com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Tag("mysql-migration")
class OAuthAuthorizationRequestPersistenceAdapterTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    JdbcTemplate jdbc;
    OAuthAuthorizationRequestPersistenceAdapter store;
    Instant now = Instant.parse("2026-09-07T00:00:00Z");
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS oauth_authorization_requests");
        jdbc.execute("DROP TABLE IF EXISTS oauth_start_rate_limits");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__create_oauth_authorization_requests.sql")).execute(ds);
        store = new OAuthAuthorizationRequestPersistenceAdapter(jdbc, new DataSourceTransactionManager(ds));
    }
    OAuthAuthorizationRequest request(String state, String caller) {
        return new OAuthAuthorizationRequest(UUID.randomUUID().toString(), state, caller, "ciphertext-nonce", "ciphertext-verifier",
            "device", Purpose.LOGIN, null, Channel.WEB, "web", Status.PENDING, now, now.plusSeconds(600));
    }
    @Test void concurrentCallbacksOnlyOneClaimSucceeds() throws Exception {
        store.create(request("state", "caller"), "source", 20, 100);
        var results = race(8, () -> {
            try { store.claim("state", "caller", Channel.WEB, Clock.fixed(now, ZoneOffset.UTC)); return true; }
            catch (OAuthRequestException e) { assertThat(e.getReason()).isEqualTo(OAuthRequestException.Reason.REPLAY); return false; }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM oauth_authorization_requests", String.class)).isEqualTo("PROCESSING");
    }
    @Test void concurrentStartsWithSpoofedBindingsCannotBypassSourceLimit() throws Exception {
        var results = race(8, () -> {
            try { store.create(request(UUID.randomUUID().toString(), UUID.randomUUID().toString()), "shared-source", 20, 3); return true; }
            catch (OAuthRequestException e) { assertThat(e.getReason()).isEqualTo(OAuthRequestException.Reason.RATE_LIMITED); return false; }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_authorization_requests", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_start_rate_limits", Integer.class)).isEqualTo(5);
    }
    @Test void callerLimitAndHourlyResetAreAtomic() {
        store.create(request("state-1", "caller"), "source-1", 1, 100);
        assertThatThrownBy(() -> store.create(request("state-2", "caller"), "source-2", 1, 100))
            .isInstanceOf(OAuthRequestException.class).extracting("reason").isEqualTo(OAuthRequestException.Reason.RATE_LIMITED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_start_rate_limits WHERE bucket_key='origin:source-2'", Integer.class)).isZero();
        now = now.plusSeconds(3600);
        assertThatCode(() -> store.create(request("state-3", "caller"), "source-2", 1, 100)).doesNotThrowAnyException();
    }
    @Test void wrongBindingOrExpiredRequestStaysUnclaimedAndCleanupIsBounded() {
        store.create(request("state-1", "caller"), "source", 20, 100);
        store.create(request("state-2", "caller"), "source", 20, 100);
        assertThatThrownBy(() -> store.claim("state-1", "wrong", Channel.WEB, Clock.fixed(now, ZoneOffset.UTC))).isInstanceOf(OAuthRequestException.class);
        assertThatThrownBy(() -> store.claim("state-1", "caller", Channel.MOBILE, Clock.fixed(now, ZoneOffset.UTC))).isInstanceOf(OAuthRequestException.class);
        assertThatThrownBy(() -> store.claim("state-1", "caller", Channel.WEB, Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC))).isInstanceOf(OAuthRequestException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_authorization_requests WHERE status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(store.cleanup(now.plusSeconds(4000), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_authorization_requests", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_start_rate_limits WHERE bucket_key='guard'", Integer.class)).isEqualTo(1);
    }
    @Test void terminalStatesCannotBeOverwrittenOrClaimedAgain() {
        var request = request("state", "caller");
        store.create(request, "source", 20, 100);
        store.claim("state", "caller", Channel.WEB, Clock.fixed(now, ZoneOffset.UTC));
        store.finish(request.id(), Status.CONSUMED);
        assertThatThrownBy(() -> store.finish(request.id(), Status.FAILED)).isInstanceOf(OAuthRequestException.class);
        assertThatThrownBy(() -> store.claim("state", "caller", Channel.WEB, Clock.fixed(now, ZoneOffset.UTC))).isInstanceOf(OAuthRequestException.class);
    }
    private List<Boolean> race(int count, Callable<Boolean> call) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i=0;i<count;i++) futures.add(pool.submit(() -> {ready.countDown(); start.await(); return call.call();}));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
}
