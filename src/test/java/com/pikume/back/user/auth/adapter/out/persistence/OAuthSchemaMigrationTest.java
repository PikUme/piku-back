package com.pikume.back.user.auth.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("mysql-migration")
class OAuthSchemaMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pikume").withUsername("pikume").withPassword("pikume");
    JdbcTemplate jdbc;

    @BeforeEach
    void migrateChapterSignupWithExistingEmailArtifacts() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var chapter = Flyway.configure().dataSource(source).cleanDisabled(false).target("18").load();
        chapter.clean();
        chapter.migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO signup_authentications
                    (id, token_hash, caller_hash, flow_type, method, verified_email, password_hash,
                     email_verified_at, authenticated_at, expires_at)
                VALUES ('email-proof', 'email-token', 'caller', 'CHAPTERED', 'EMAIL',
                        'member@gmail.com', 'password-hash', '2026-09-26 00:00:00',
                        '2026-09-26 00:00:00', '2026-09-26 00:10:00')
                """);
        jdbc.update("""
                INSERT INTO verification
                    (email, code, type, expires_at, challenge_id, caller_hash, attempts, sent_at,
                     resend_available_at, delivery_completed_at)
                VALUES ('member@gmail.com', 'code-hash', 'SIGN_UP', '2026-09-26 00:05:00',
                        'email-challenge', 'caller', 1, '2026-09-26 00:00:00',
                        '2026-09-26 00:01:00', '2026-09-26 00:00:01')
                """);
        user("existing");
        jdbc.update("""
                INSERT INTO user_agreements
                    (id, user_id, agreement_type, agreement_version, content, agreed, accepted_at)
                VALUES ('agreement', 'existing', 'TERMS', 'v1', 'actual terms', TRUE, '2026-09-26 00:00:00')
                """);
        Flyway.configure().dataSource(source).target("19").load().migrate();
    }

    @Test
    void googleUpgradePreservesChapterEmailProofsChallengesAndAgreements() {
        var proof = jdbc.queryForMap("SELECT * FROM signup_authentications WHERE id='email-proof'");
        assertThat(proof).containsEntry("token_hash", "email-token")
                .containsEntry("caller_hash", "caller").containsEntry("method", "EMAIL")
                .containsEntry("verified_email", "member@gmail.com").containsEntry("password_hash", "password-hash")
                .containsEntry("email_verification_source", "SERVICE")
                .containsEntry("provider", null).containsEntry("provider_subject", null)
                .containsEntry("consumed_at", null).containsEntry("result_user_id", null);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, authenticated_at, expires_at) FROM signup_authentications WHERE id='email-proof'", Integer.class)).isEqualTo(600);
        var challenge = jdbc.queryForMap("SELECT * FROM verification WHERE challenge_id='email-challenge'");
        assertThat(challenge).containsEntry("email", "member@gmail.com").containsEntry("code", "code-hash")
                .containsEntry("caller_hash", "caller").containsEntry("attempts", 1)
                .containsEntry("signup_proof_hash", null).containsEntry("consumed_at", null);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, sent_at, expires_at) FROM verification WHERE challenge_id='email-challenge'", Integer.class)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT content FROM user_agreements WHERE id='agreement'", String.class)).isEqualTo("actual terms");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_oauth_accounts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_authorization_requests", Integer.class)).isZero();
    }

    @Test
    void providerSubjectsKeepCaseAndTrailingSpacesDistinct() {
        user("u1");
        user("u2");
        user("u3");
        link("l1", "u1", "Subject");
        link("l2", "u2", "subject");
        link("l3", "u3", "Subject ");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_oauth_accounts", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_oauth_accounts WHERE provider='GOOGLE' AND provider_subject='Subject'", String.class)).isEqualTo("u1");
    }

    @Test
    void providerSubjectAndUserProviderEachRemainUnique() {
        user("u1");
        user("u2");
        link("l1", "u1", "subject");

        assertThatThrownBy(() -> link("l2", "u2", "subject")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> link("l3", "u1", "different-subject")).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void user(String id) {
        jdbc.update("INSERT INTO users (id,email,nickname,character_id) VALUES (?,?,?,1)", id, id+"@gmail.com", id);
    }

    private void link(String id, String user, String subject) {
        jdbc.update("INSERT INTO user_oauth_accounts (id,user_id,provider,provider_subject,linked_at) VALUES (?,?,'GOOGLE',?,NOW())", id, user, subject);
    }
}
