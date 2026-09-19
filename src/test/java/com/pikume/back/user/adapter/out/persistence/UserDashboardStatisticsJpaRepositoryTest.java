package com.pikume.back.user.adapter.out.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pikume.back.user.application.port.out.QueryUserStatisticsPort;
import com.pikume.back.user.domain.ProfileSetupStatus;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("UserJpaRepository dashboard statistics")
class UserDashboardStatisticsJpaRepositoryTest {

	@Autowired
	private UserJpaRepository userJpaRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("누적 회원 수는 탈퇴 여부와 관계없이 현재와 기준 시점까지의 가입 이력을 집계한다")
	void countsCumulativeMembersAtCutoff() {
		LocalDateTime cutoff = LocalDate.of(2026, 6, 16).atStartOfDay();
		insertUser("active", LocalDateTime.of(2026, 6, 1, 10, 0), null);
		insertUser("deleted-after", LocalDateTime.of(2026, 6, 2, 10, 0), cutoff.plusDays(1));
		insertUser("deleted-before", LocalDateTime.of(2026, 6, 3, 10, 0), cutoff.minusDays(1));
		insertUser("joined-after", cutoff.plusHours(1), null);
		UserStatisticsPersistenceAdapter adapter = new UserStatisticsPersistenceAdapter(userJpaRepository);

		assertThat(adapter.countAllMembers()).isEqualTo(4);
		assertThat(adapter.countMembersBefore(cutoff)).isEqualTo(3);
	}

	@Test
	@DisplayName("통합 대시보드 일간 신규 가입자는 탈퇴 여부와 관계없이 가입 이력을 포함한다")
	void countsAllSignupMembersByDate() {
		LocalDate date = LocalDate.of(2026, 6, 16);
		insertUser("active", date.atTime(10, 0), null);
		insertUser("deleted", date.atTime(11, 0), date.plusDays(1).atStartOfDay());

		UserStatisticsPersistenceAdapter adapter = new UserStatisticsPersistenceAdapter(userJpaRepository);
		var rows = adapter.countAllSignupMembersByDate(date, date);
		var activeRows = adapter.countActiveSignupMembersByDate(date, date);

		assertThat(rows).singleElement().satisfies(row ->
				assertThat(row).isEqualTo(new QueryUserStatisticsPort.DailyCount(date, 2L)));
		assertThat(activeRows).singleElement().satisfies(row ->
				assertThat(row).isEqualTo(new QueryUserStatisticsPort.DailyCount(date, 1L)));
	}

	@Test
	@DisplayName("공개 활성 회원 통계는 프로필 설정 완료 회원만 집계하고 운영 누적 통계는 가입 대기도 유지한다")
	void excludesPendingUsersOnlyFromPublicActiveStatistics() {
		LocalDate date = LocalDate.of(2026, 6, 16);
		insertUser("completed", date.atTime(10, 0), null, ProfileSetupStatus.COMPLETED);
		insertUser("pending", date.atTime(11, 0), null, ProfileSetupStatus.REQUIRED);
		UserStatisticsPersistenceAdapter adapter = new UserStatisticsPersistenceAdapter(userJpaRepository);

		assertThat(adapter.countActiveMembers()).isEqualTo(1L);
		assertThat(adapter.countActiveSignupMembersByDate(date, date))
				.containsExactly(new QueryUserStatisticsPort.DailyCount(date, 1L));
		assertThat(adapter.countAllMembers()).isEqualTo(2L);
		assertThat(adapter.countAllSignupMembersByDate(date, date))
				.containsExactly(new QueryUserStatisticsPort.DailyCount(date, 2L));
	}

	private void insertUser(String suffix, LocalDateTime createdAt, LocalDateTime deletedAt) {
		insertUser(suffix, createdAt, deletedAt, ProfileSetupStatus.COMPLETED);
	}

	private void insertUser(String suffix, LocalDateTime createdAt, LocalDateTime deletedAt,
			ProfileSetupStatus profileSetupStatus) {
		jdbcTemplate.update("""
					INSERT INTO users (id, email, password, nickname, character_id, profile_setup_status,
					                   created_at, updated_at, deleted_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				"user-" + suffix,
				suffix + "@example.com",
					"password",
					"nick-" + suffix,
					1L,
				profileSetupStatus.name(),
				Timestamp.valueOf(createdAt),
				Timestamp.valueOf(createdAt),
				deletedAt == null ? null : Timestamp.valueOf(deletedAt));
	}
}
