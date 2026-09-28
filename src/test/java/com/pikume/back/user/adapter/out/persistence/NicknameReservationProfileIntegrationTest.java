package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.ResolveFixedCharacterAvatarPort;
import com.pikume.back.user.application.service.UserProfileCommandService;
import com.pikume.back.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({UserProfileCommandService.class, UserAccountPersistenceAdapter.class,
 UserPersistenceAdapter.class, NicknameHoldPersistenceAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NicknameReservationProfileIntegrationTest {
 @Autowired UserProfileCommandService service;
 @Autowired UserJpaRepository users;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean ResolveFixedCharacterAvatarPort characters;
 @BeforeEach void setup() {
  jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_write_mutex (id INT PRIMARY KEY)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS nickname_holds (nickname VARCHAR(255) PRIMARY KEY, owner_key VARCHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP(6) NOT NULL)");
  jdbc.update("MERGE INTO nickname_write_mutex (id) KEY(id) VALUES (1)");
  jdbc.update("DELETE FROM nickname_holds");
  users.deleteAll();
 }
	@Test void completedProfileUsesTheSameNormalizedDatabaseHoldForReservationAndUpdate() {
		User user=users.saveAndFlush(new User("profile@example.com","hash","현재닉",1L));
		assertThat(service.reserveIfAvailable("  새닉　 ",user.getId())).isTrue();
		var result=service.updateProfile(new com.pikume.back.user.application.dto.UpdateProfileCommand(user.getId(),"\t새닉\n",null));
		assertThat(result.success()).isTrue();assertThat(result.newNickname()).isEqualTo("새닉");
		assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,user.getId())).isEqualTo("새닉");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nickname_holds",Integer.class)).isZero();
	}

}
