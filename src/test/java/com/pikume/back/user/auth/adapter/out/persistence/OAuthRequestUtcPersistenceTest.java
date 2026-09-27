package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.domain.OAuthAuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.pikume.back.user.auth.domain.OAuthAuthorizationRequest.*;

class OAuthRequestUtcPersistenceTest {
    @Test void requestAndRateWindowAreStoredAsUtcAcrossDifferentServerTimezones() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var ds = new DriverManagerDataSource("jdbc:h2:mem:oauth_utc;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("DROP ALL OBJECTS");
            jdbc.execute("""
                CREATE TABLE oauth_authorization_requests(id VARCHAR(36) PRIMARY KEY,state_hash VARCHAR(64) UNIQUE,
                caller_binding_hash VARCHAR(64),encrypted_nonce VARCHAR(256),encrypted_code_verifier VARCHAR(256),
                device_id VARCHAR(128),purpose VARCHAR(10),target_user_id VARCHAR(36),channel VARCHAR(10),
                registration VARCHAR(64),status VARCHAR(16),created_at DATETIME(6),expires_at DATETIME(6))
                """);
            jdbc.execute("CREATE TABLE oauth_start_rate_limits(bucket_key VARCHAR(72) PRIMARY KEY,request_count INT,window_started_at DATETIME(6))");
            jdbc.execute("INSERT INTO oauth_start_rate_limits VALUES ('guard',0,'2026-09-07 00:00:00')");
            var store = new OAuthAuthorizationRequestPersistenceAdapter(jdbc, new DataSourceTransactionManager(ds));
            Instant now = Instant.parse("2026-09-07T00:00:00Z");
            var request = new OAuthAuthorizationRequest("request", "state", "caller", "nonce", "verifier", "device",
                Purpose.LOGIN, null, Channel.WEB, "web", Status.PENDING, now, now.plusSeconds(600));
            store.create(request, "origin", 20, 100);
            assertThat(jdbc.queryForObject("SELECT expires_at FROM oauth_authorization_requests", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 9, 7, 0, 10));
            assertThat(jdbc.queryForObject("SELECT window_started_at FROM oauth_start_rate_limits WHERE bucket_key='origin:origin'", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 9, 7, 0, 0));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            assertThat(store.claim("state", "caller", Channel.WEB, java.time.Clock.fixed(now.plusSeconds(599), java.time.ZoneOffset.UTC)).expiresAt()).isEqualTo(now.plusSeconds(600));
        } finally { TimeZone.setDefault(original); }
    }
}
