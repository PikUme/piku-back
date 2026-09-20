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
