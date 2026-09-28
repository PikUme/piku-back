-- Acquire after any email verification row and before user/hold rows; retain until commit.
CREATE TABLE nickname_write_mutex (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO nickname_write_mutex (id) VALUES (1);

-- Match users.nickname equality, including MySQL accent and case handling.
CREATE TABLE nickname_holds (
    nickname VARCHAR(255) NOT NULL,
    owner_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (nickname),
    CONSTRAINT uk_nickname_holds_owner UNIQUE (owner_key),
    KEY idx_nickname_holds_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
