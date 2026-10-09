CREATE TABLE nickname_write_mutex (
    id TINYINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_nickname_write_mutex_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO nickname_write_mutex (id) VALUES (1);
