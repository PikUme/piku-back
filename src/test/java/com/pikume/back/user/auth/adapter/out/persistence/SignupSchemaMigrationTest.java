package com.pikume.back.user.auth.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Tag("mysql-migration")
class SignupSchemaMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pikume").withUsername("pikume").withPassword("pikume");
    JdbcTemplate jdbc;
    @BeforeEach void migrate() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var old=Flyway.configure().dataSource(source).cleanDisabled(false).target("16").load();
        old.clean();old.migrate();jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO verification (email,code,type,expires_at) VALUES ('legacy@gmail.com','123456','SIGN_UP',NOW())");
        Flyway.configure().dataSource(source).target("17").load().migrate();
    }
    void user(String id) {
        jdbc.update("INSERT INTO users (id,email,nickname,character_id) VALUES (?,?,?,1)",id,id+"@gmail.com",id);
    }
    void link(String id,String user,String subject) {
        jdbc.update("INSERT INTO user_oauth_accounts (id,user_id,provider,provider_subject,linked_at) VALUES (?,?,'GOOGLE',?,NOW())",id,user,subject);
    }
    @Test void preservesLegacyVerificationAndStoresStrictSubjectComparison() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification WHERE challenge_id IS NULL AND caller_hash IS NULL AND attempts IS NULL",Integer.class)).isEqualTo(1);
        user("u1");user("u2");user("u3");
        link("l1","u1","Subject");link("l2","u2","subject");link("l3","u3","Subject ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_oauth_accounts",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_oauth_accounts WHERE provider='GOOGLE' AND provider_subject='Subject'",String.class)).isEqualTo("u1");
    }
    @Test void enforcesBothSocialUniquenessConstraints() {
        user("u1");user("u2");link("l1","u1","subject");
        assertThatThrownBy(() -> link("l2","u2","subject")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> link("l3","u1","different-subject")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void challengeIdentifiersAreUniqueButLegacyNullIdentifiersCanRepeat() {
        jdbc.update("INSERT INTO verification (email,code,type,expires_at,challenge_id) VALUES ('new@gmail.com','hash','SIGN_UP',NOW(),'challenge')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO verification (email,code,type,expires_at,challenge_id) VALUES ('other@gmail.com','hash','SIGN_UP',NOW(),'challenge')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO verification (email,code,type,expires_at) VALUES ('legacy2@gmail.com','123456','SIGN_UP',NOW())");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification WHERE challenge_id IS NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signup_rate_limits WHERE bucket_key='guard'",Integer.class)).isEqualTo(1);
    }
}
