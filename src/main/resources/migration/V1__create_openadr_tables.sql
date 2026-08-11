CREATE TABLE IF NOT EXISTS ven_registration (
                                                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                ven_id          VARCHAR(64)  NOT NULL,
    vtn_id          VARCHAR(64),
    registration_id VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    requested_poll_frequency VARCHAR(64) NULL,
    registered_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_ven_id (ven_id)
    );

CREATE TABLE IF NOT EXISTS dr_event (
                                        id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        event_id         VARCHAR(64)  NOT NULL UNIQUE,
    modification_number INT NOT NULL DEFAULT 0,
    status           VARCHAR(32)  NOT NULL,
    opt_type         VARCHAR(16),
    priority         INT,
    start_time DATETIME(3) NOT NULL,
    duration_seconds BIGINT,
    raw_payload      TEXT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
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
    requested_rids VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL
    );

CREATE TABLE IF NOT EXISTS opt_schedule (
                                            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            opt_id          VARCHAR(64) NOT NULL UNIQUE,
    opt_type        VARCHAR(16) NOT NULL,
    opt_reason      VARCHAR(64),
    event_id        VARCHAR(64),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_opt_id (opt_id),
    INDEX idx_event_id (event_id)
    );

CREATE INDEX idx_ven_report_spec_id ON ven_report(report_spec_id);
CREATE INDEX idx_ven_report_request_id ON ven_report(report_request_id);
CREATE UNIQUE INDEX uk_ven_report_spec_id ON ven_report(report_spec_id);