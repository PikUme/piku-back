package com.pikume.back.user.auth.adapter.in.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("mysql-migration")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EmailVerificationHttpMySqlIntegrationTest extends EmailVerificationHttpIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.pikume.back.user.auth.application.port.out.PasswordProtectionPort passwords;
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username",MYSQL::getUsername);
        properties.add("spring.datasource.password",MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.MySQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
        properties.add("spring.flyway.enabled",()->"true");
    }
    @Test void concurrentCodeVerificationIssuesOnlyOneToken() throws Exception {
        send("member@gmail.com");
        var workers=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
        try {
            Callable<Integer> verify=()->{ready.await(5,TimeUnit.SECONDS);return verify("member@gmail.com","123456").andReturn().getResponse().getStatus();};
            var first=workers.submit(verify);var second=workers.submit(verify);
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally {workers.shutdownNow();}
    }
    @Test void concurrentSignupConsumesProofExactlyOnce() throws Exception {
        String token=proof("member@gmail.com");
        var workers=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
        try {
            Callable<Integer> signup=()->{ready.await(5,TimeUnit.SECONDS);return signup("member@gmail.com",token,"회원",characterId).andReturn().getResponse().getStatus();};
            var first=workers.submit(signup);var second=workers.submit(signup);
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isEqualTo(1);
        } finally {workers.shutdownNow();}
    }
    @Test void concurrentGuestReservationAndSignupHaveOneNicknameWinner() throws Exception {
        String owner=proof("owner@gmail.com"), other=proof("other@gmail.com");
        var workers=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
        try {
            var hold=workers.submit(()->{ready.await(5,TimeUnit.SECONDS);return reserve(owner,"동시닉").andReturn().getResponse().getStatus();});
            var signup=workers.submit(()->{ready.await(5,TimeUnit.SECONDS);return signup("other@gmail.com",other,"동시닉",characterId).andReturn().getResponse().getStatus();});
            int reserved=hold.get(15,TimeUnit.SECONDS), created=signup.get(15,TimeUnit.SECONDS);
            assertThat((reserved==200 && created==409)||(reserved==409 && created==201)).isTrue();
        } finally {workers.shutdownNow();}
    }
    @Test void concurrentReservationAndSignupWithTheSameProofUseOneLockOrder() throws Exception {
        String token=proof("member@gmail.com");
        var workers=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
        try {
            var hold=workers.submit(()->{ready.await(5,TimeUnit.SECONDS);return reserve(token,"동시닉").andReturn().getResponse().getStatus();});
            var signup=workers.submit(()->{ready.await(5,TimeUnit.SECONDS);return signup("member@gmail.com",token,"동시닉",characterId).andReturn().getResponse().getStatus();});
            assertThat(signup.get(15,TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(hold.get(15,TimeUnit.SECONDS)).isIn(200,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds",Integer.class)).isZero();
        } finally {workers.shutdownNow();}
    }

    @Test void passwordResetCannotOverwriteNicknameChangesAndSubsequentGuestReservations() throws Exception {
        String member=proof("member@gmail.com");
        signup("member@gmail.com",member,"이전닉",characterId).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        String memberId=jdbc.queryForObject("SELECT id FROM users WHERE email=?",String.class,"member@gmail.com");
        assertThat(profiles.reserveIfAvailable("변경닉",memberId)).isTrue();
        String guest=proof("guest@gmail.com");
        request("/send-verification/password-reset",java.util.Map.of("email","member@gmail.com"));
        request("/verify-code",java.util.Map.of("email","member@gmail.com","code","123456","type","PASSWORD_RESET"));
        var passwordWrite=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{
            passwordWrite.countDown();
            if (!proceed.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
            return invocation.callRealMethod();
        }).when(passwords).protect("new@123");
        var workers=Executors.newFixedThreadPool(2);
        try {
            var reset=workers.submit(()->request("/password-reset",java.util.Map.of("email","member@gmail.com","password","new@123")).andReturn().getResponse().getStatus());
            assertThat(passwordWrite.await(5,TimeUnit.SECONDS)).isTrue();
            var changed=workers.submit(()->{
                assertThat(profiles.updateProfile(new com.pikume.back.user.application.dto.UpdateProfileCommand(memberId,"변경닉",null)).success()).isTrue();
                return reserve(guest,"이전닉").andReturn().getResponse().getStatus();
            });
            try { changed.get(300,TimeUnit.MILLISECONDS); } catch (TimeoutException expectedWhileResetOwnsTheUserRow) { }
            proceed.countDown();
            assertThat(reset.get(10,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(changed.get(10,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,memberId)).isEqualTo("변경닉");
            signup("guest@gmail.com",guest,"이전닉",characterId).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        } finally {proceed.countDown();workers.shutdownNow();}
    }

}
