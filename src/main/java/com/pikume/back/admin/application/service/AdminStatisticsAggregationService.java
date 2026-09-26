package com.pikume.back.admin.application.service;

import com.pikume.back.admin.application.port.in.AdminStatisticsAggregationUseCase;
import com.pikume.back.admin.application.port.out.RecordAdminDailyStatisticsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminStatisticsAggregationService implements AdminStatisticsAggregationUseCase {

	private static final ZoneId STATISTICS_ZONE = ZoneId.of("Asia/Seoul");

	private final AdminDailyStatisticsCalculator adminDailyStatisticsCalculator;
	private final RecordAdminDailyStatisticsPort recordAdminDailyStatisticsPort;

	@Override
	@Transactional
	public void aggregateYesterday() {
		aggregate(LocalDate.now(STATISTICS_ZONE).minusDays(1));
	}

	@Override
	@Transactional
	public void aggregate(LocalDate date) {
		Map<LocalDate, AdminDailyStatisticsResult> calculated = adminDailyStatisticsCalculator.calculate(date, date);
		AdminDailyStatisticsResult result = calculated.getOrDefault(date, AdminDailyStatisticsResult.zero(date));
		recordAdminDailyStatisticsPort.recordDailyStatistics(result.toEntity(LocalDateTime.now(STATISTICS_ZONE)));
		log.info("event=admin_daily_statistics_aggregated outcome=success date={}", date);
	}
}
