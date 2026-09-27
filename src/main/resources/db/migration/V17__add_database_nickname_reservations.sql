-- All nickname writers acquire this row before user/hold locks and keep it until commit.
CREATE TABLE nickname_write_mutex (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO nickname_write_mutex (id) VALUES (1);

-- Match users.nickname equality, including MySQL accent and case handling.
CREATE TABLE nickname_holds (
    nickname VARCHAR(255) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (nickname),
    CONSTRAINT uk_nickname_holds_user UNIQUE (user_id),
    KEY idx_nickname_holds_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
