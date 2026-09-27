ALTER TABLE verification
    ADD COLUMN challenge_id VARCHAR(36) DEFAULT NULL,
    ADD COLUMN caller_hash VARCHAR(64) DEFAULT NULL,
    ADD COLUMN attempts INT DEFAULT NULL,
    ADD COLUMN sent_at DATETIME(6) DEFAULT NULL,
    ADD COLUMN resend_available_at DATETIME(6) DEFAULT NULL,
    ADD COLUMN consumed_at DATETIME(6) DEFAULT NULL,
    ADD COLUMN delivery_completed_at DATETIME(6) DEFAULT NULL,
    ADD UNIQUE KEY uk_verification_challenge (challenge_id),
    ADD KEY idx_verification_challenge_expiry (expires_at, challenge_id);

CREATE TABLE signup_rate_limits (
    bucket_key VARCHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    window_started_at DATETIME(6) NOT NULL,
    send_count INT NOT NULL,
    last_sent_at DATETIME(6) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO signup_rate_limits (bucket_key, window_started_at, send_count)
VALUES ('guard', '1970-01-01 00:00:00', 0);
