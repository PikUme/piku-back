-- OAuth storage belongs to the Google login rollout, after chapter signup V16-V18.
ALTER TABLE signup_authentications
    ADD COLUMN provider VARCHAR(20) DEFAULT NULL AFTER method,
    ADD COLUMN provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin DEFAULT NULL AFTER provider,
    ADD COLUMN email_verification_source VARCHAR(20) DEFAULT NULL AFTER password_hash;

-- Existing chapter and legacy email proofs were verified by the service.
UPDATE signup_authentications
SET email_verification_source = 'SERVICE'
WHERE method = 'EMAIL';

-- Compatibility guard for historical social-email challenges; new email challenges leave this null.
ALTER TABLE verification
    ADD COLUMN signup_proof_hash VARCHAR(64) DEFAULT NULL AFTER caller_hash;

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

-- Requests contain hashes/ciphertext only; codes, ID tokens and session credentials never enter this table.
CREATE TABLE oauth_authorization_requests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    caller_binding_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encrypted_nonce VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encrypted_code_verifier VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(10) NOT NULL,
    target_user_id VARCHAR(36) NULL,
    channel VARCHAR(10) NOT NULL,
    registration VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth_authorization_state (state_hash),
    KEY idx_oauth_authorization_expiry (expires_at),
    CONSTRAINT chk_oauth_request_purpose CHECK ((purpose='LOGIN' AND target_user_id IS NULL) OR (purpose='LINK' AND target_user_id IS NOT NULL)),
    CONSTRAINT chk_oauth_request_channel CHECK ((channel='WEB' AND registration='web' AND encrypted_code_verifier IS NOT NULL) OR (channel='MOBILE' AND registration<>'web' AND encrypted_code_verifier IS NULL)),
    CONSTRAINT chk_oauth_request_status CHECK (status IN ('PENDING','PROCESSING','CONSUMED','FAILED'))
) ENGINE=InnoDB;

-- A permanent mutex serializes reservations even for previously unseen callers/sources.
CREATE TABLE oauth_start_rate_limits (
    bucket_key VARCHAR(72) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_count INT NOT NULL,
    window_started_at DATETIME(6) NOT NULL,
    PRIMARY KEY (bucket_key),
    KEY idx_oauth_start_rate_limit_expiry (window_started_at)
) ENGINE=InnoDB;
INSERT INTO oauth_start_rate_limits (bucket_key,request_count,window_started_at) VALUES ('guard',0,UTC_TIMESTAMP(6));
