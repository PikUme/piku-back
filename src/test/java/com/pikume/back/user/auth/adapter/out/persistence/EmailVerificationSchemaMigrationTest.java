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
class EmailVerificationSchemaMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pikume").withUsername("pikume").withPassword("pikume");
    JdbcTemplate jdbc;
    @BeforeEach void migrate() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var old=Flyway.configure().dataSource(source).cleanDisabled(false).target("15").load();
        old.clean();old.migrate();jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO verification (email,code,type,expires_at) VALUES ('legacy@gmail.com','123456','SIGN_UP',NOW())");
        Flyway.configure().dataSource(source).target("16").load().migrate();
    }
    @Test void preservesLegacyVerification() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification WHERE email_verification_id IS NULL AND verification_token_hash IS NULL AND attempts IS NULL",Integer.class)).isEqualTo(1);
    }

    @Test void emailVerificationIdentifiersAreUniqueButLegacyNullIdentifiersCanRepeat() {
        jdbc.update("INSERT INTO verification (email,code,type,expires_at,email_verification_id) VALUES ('new@gmail.com','hash','SIGN_UP',NOW(),'verification')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO verification (email,code,type,expires_at,email_verification_id) VALUES ('other@gmail.com','hash','SIGN_UP',NOW(),'verification')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO verification (email,code,type,expires_at) VALUES ('legacy2@gmail.com','123456','SIGN_UP',NOW())");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification WHERE email_verification_id IS NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_rate_limits WHERE bucket_key='guard'",Integer.class)).isEqualTo(1);
    }
}
