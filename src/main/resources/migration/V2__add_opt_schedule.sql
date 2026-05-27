CREATE TABLE IF NOT EXISTS opt_schedule (
                                            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            opt_id          VARCHAR(64) NOT NULL UNIQUE,
    opt_type        VARCHAR(16) NOT NULL,
    opt_reason      VARCHAR(64),
    event_id        VARCHAR(64),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME,
    updated_at      DATETIME,
    INDEX idx_opt_id (opt_id),
    INDEX idx_event_id (event_id)
    );