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

	@Container
	static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url",MYSQL::getJdbcUrl);
		properties.add("spring.datasource.username",MYSQL::getUsername);
		properties.add("spring.datasource.password",MYSQL::getPassword);
		properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
		properties.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.MySQLDialect");
		properties.add("spring.jpa.hibernate.ddl-auto",()->"none");
		properties.add("spring.flyway.enabled",()->"true");
	}

	@Test
	void concurrentCodeVerificationIssuesOnlyOneToken() throws Exception {
		send("member@gmail.com");
		var workers=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
		try {
			Callable<Integer> verify=()->{ready.await(5,TimeUnit.SECONDS);return verify("member@gmail.com","123456").andReturn().getResponse().getStatus();};
			var first=workers.submit(verify);var second=workers.submit(verify);
			assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
		} finally {workers.shutdownNow();}
	}

	@Test
	void concurrentSignupConsumesProofExactlyOnce() throws Exception {
		String token=proof("member@gmail.com");
		var workers=Executors.newFixedThreadPool(2);var ready=new CyclicBarrier(2);
		try {
			Callable<Integer> signup=()->{ready.await(5,TimeUnit.SECONDS);return signup("member@gmail.com",token,"회원",characterId).andReturn().getResponse().getStatus();};
			var first=workers.submit(signup);var second=workers.submit(signup);
			assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isEqualTo(1);
		} finally {workers.shutdownNow();}
	}
}
