package com.pikume.back.user.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_verification_rate_limits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class EmailVerificationRateLimit {

	@Id
	@Column(length = 70)
	private String bucketKey;

	@Column(nullable = false)
	private LocalDateTime windowStartedAt;

	@Column(nullable = false)
	private int sendCount;

	private LocalDateTime lastSentAt;

	EmailVerificationRateLimit(String key, LocalDateTime now) {
		bucketKey = key;
		windowStartedAt = now;
	}

	void resetIfExpired(LocalDateTime now) {
		if (!now.isBefore(windowStartedAt.plusSeconds(3600))) {
			windowStartedAt = now;
			sendCount = 0;
		}
	}

	void increment(LocalDateTime now) {
		sendCount++;
		lastSentAt = now;
	}
}
