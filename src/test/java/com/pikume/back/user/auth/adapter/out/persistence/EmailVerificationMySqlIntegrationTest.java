package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.dto.SendEmailVerificationCommand;
import com.pikume.back.user.auth.application.dto.EmailVerificationDelivery;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
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
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EmailVerificationMySqlIntegrationTest extends EmailVerificationPersistenceIntegrationTest {
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

 @Test void concurrentFirstRequestsShareTheEmailCooldownAcrossConnections() throws Exception {
  var workers=Executors.newFixedThreadPool(2);
  var ready=new CyclicBarrier(2);
  try {
   List<Future<Object>> requests=List.of("first-origin","second-origin").stream().map(origin->workers.submit(()->{
    ready.await(5,TimeUnit.SECONDS);
    try {return (Object)service.sendEmailCode(new SendEmailVerificationCommand("a@gmail.com",origin));}
    catch(EmailVerificationException error) {return error.getReason();}
   })).toList();
   var results=List.of(requests.get(0).get(15,TimeUnit.SECONDS),requests.get(1).get(15,TimeUnit.SECONDS));
   assertThat(results.stream().filter(EmailVerificationDelivery.class::isInstance)).hasSize(1);
   assertThat(results).contains(com.pikume.back.user.auth.application.exception.EmailVerificationFailure.RATE_LIMITED);
   assertThat(count("Verification")).isEqualTo(1);
  } finally {workers.shutdownNow();}
 }
}
