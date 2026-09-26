package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.QueryUserStatisticsPort;
import com.pikume.back.user.domain.ProfileSetupStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class UserStatisticsPersistenceAdapter implements QueryUserStatisticsPort {

	private final UserJpaRepository jpaRepository;

	@Override
	public long countActiveMembers() {
		return jpaRepository.countByDeletedAtIsNullAndProfileSetupStatus(ProfileSetupStatus.COMPLETED);
	}

	@Override
	public long countAllMembers() {
		return jpaRepository.count();
	}

	@Override
	public long countMembersBefore(LocalDateTime cutoffExclusive) {
		return jpaRepository.countByCreatedAtBefore(cutoffExclusive);
	}

	@Override
	public List<DailyCount> countActiveSignupMembersByDate(LocalDate startDate, LocalDate endDate) {
		return toDailyCounts(jpaRepository.countSignupMembersByDate(
				startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay()));
	}

	@Override
	public List<DailyCount> countAllSignupMembersByDate(LocalDate startDate, LocalDate endDate) {
		return toDailyCounts(jpaRepository.countAllSignupMembersByDate(
				startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay()));
	}

	private List<DailyCount> toDailyCounts(List<UserJpaRepository.DailyCountProjection> rows) {
		return rows.stream()
				.map(row -> new DailyCount(toLocalDate(row.getMetricDate()), row.getMetricCount()))
				.toList();
	}

	private LocalDate toLocalDate(Object value) {
		if (value instanceof LocalDate localDate) {
			return localDate;
		}
		if (value instanceof Date date) {
			return date.toLocalDate();
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.toLocalDate();
		}
		return LocalDate.parse(String.valueOf(value));
	}
}
