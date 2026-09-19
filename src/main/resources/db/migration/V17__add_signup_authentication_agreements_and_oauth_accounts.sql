CREATE TABLE signup_authentications (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    caller_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    flow_type VARCHAR(20) NOT NULL,
    method VARCHAR(20) NOT NULL,
    provider VARCHAR(20) DEFAULT NULL,
    provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin DEFAULT NULL,
    verified_email VARCHAR(255) DEFAULT NULL,
    password_hash VARCHAR(255) DEFAULT NULL,
    email_verification_source VARCHAR(20) DEFAULT NULL,
    email_verified_at DATETIME(6) DEFAULT NULL,
    authenticated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) DEFAULT NULL,
    result_user_id VARCHAR(36) DEFAULT NULL,
    completion_fingerprint VARCHAR(64) DEFAULT NULL,
    UNIQUE KEY uk_signup_token_hash (token_hash),
    KEY idx_signup_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_oauth_accounts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    provider VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_oauth_provider_subject (provider, provider_subject),
    UNIQUE KEY uk_oauth_user_provider (user_id, provider),
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_agreements (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    agreement_type VARCHAR(50) NOT NULL,
    agreement_version VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    agreed BIT(1) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_agreement_version (user_id, agreement_type, agreement_version),
    CONSTRAINT fk_agreement_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE verification
    ADD COLUMN challenge_id VARCHAR(36) DEFAULT NULL,
    ADD COLUMN caller_hash VARCHAR(64) DEFAULT NULL,
    ADD COLUMN signup_proof_hash VARCHAR(64) DEFAULT NULL,
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
