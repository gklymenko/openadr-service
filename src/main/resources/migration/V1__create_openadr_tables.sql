CREATE TABLE IF NOT EXISTS ven_registration (
                                                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                ven_id          VARCHAR(64)  NOT NULL,
    vtn_id          VARCHAR(64),
    registration_id VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    registered_at   DATETIME,
    updated_at      DATETIME,
    INDEX idx_ven_id (ven_id)
    );

CREATE TABLE IF NOT EXISTS dr_event (
                                        id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        event_id         VARCHAR(64)  NOT NULL UNIQUE,
    modification_number INT        DEFAULT 0,
    status           VARCHAR(32)  NOT NULL,
    opt_type         VARCHAR(16),
    priority         INT,
    start_time       DATETIME     NOT NULL,
    duration_seconds BIGINT,
    raw_payload      TEXT,
    created_at       DATETIME,
    updated_at       DATETIME,
    INDEX idx_event_id (event_id),
    INDEX idx_status (status)
    );

CREATE TABLE IF NOT EXISTS ven_report (
                                          id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                                          report_spec_id    VARCHAR(64),
    report_request_id VARCHAR(64),
    report_name       VARCHAR(64),
    status            VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
    granularity_seconds INT,
    created_at        DATETIME,
    updated_at        DATETIME
    );