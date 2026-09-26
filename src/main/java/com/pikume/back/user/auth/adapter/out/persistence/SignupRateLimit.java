package com.pikume.back.user.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity @Table(name="signup_rate_limits")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
class SignupRateLimit {
    @Id @Column(length=70) private String bucketKey;
    @Column(nullable=false) private Instant windowStartedAt;
    @Column(nullable=false) private int sendCount;
    private Instant lastSentAt;
    SignupRateLimit(String key, Instant now) {
        bucketKey=key;
        windowStartedAt=now;
    }
    void resetIfExpired(Instant now) {
        if (!now.isBefore(windowStartedAt.plusSeconds(3600))) {
            windowStartedAt=now;
            sendCount=0;
        }
    }
    void increment(Instant now) {
        sendCount++;
        lastSentAt = now;
    }
}
