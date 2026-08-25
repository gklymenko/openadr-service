CREATE TABLE IF NOT EXISTS ven_registration (
                                                id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                ven_id          VARCHAR(64)  NOT NULL,
    vtn_id          VARCHAR(64),
    registration_id VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    requested_poll_frequency VARCHAR(64) NOT NULL,
    registered_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT chk_ven_registration_ven_id_not_blank
        CHECK (CHAR_LENGTH(TRIM(ven_id)) > 0),
    CONSTRAINT chk_ven_registration_registration_id_not_blank
        CHECK (CHAR_LENGTH(TRIM(registration_id)) > 0),
    CONSTRAINT chk_ven_registration_poll_frequency_not_blank CHECK (CHAR_LENGTH(TRIM(requested_poll_frequency)) > 0)
    INDEX idx_ven_id (ven_id)
    );

CREATE TABLE IF NOT EXISTS dr_event (
                                        id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        event_id         VARCHAR(64)  NOT NULL UNIQUE,
    modification_number INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    ven_status           VARCHAR(32)  NOT NULL,
    vtn_status VARCHAR(32) NOT NULL,
    execution_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    opt_type         VARCHAR(16),
    priority         INT,
    test_event BOOLEAN NOT NULL DEFAULT FALSE,
    start_time DATETIME(3) NOT NULL,
    requested_start_time DATETIME(3) NOT NULL,
    start_after_seconds BIGINT NOT NULL DEFAULT 0,
    random_offset_seconds BIGINT NOT NULL DEFAULT 0,
    ramp_up_seconds BIGINT NULL,
    recovery_seconds BIGINT NULL,
    duration_seconds BIGINT,
    last_applied_interval INT NOT NULL DEFAULT -1,
    applied_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    cancellation_type VARCHAR(16) NULL,
    cancellation_requested_at DATETIME(3) NULL,
    cancellation_effective_at DATETIME(3) NULL,
    raw_payload      TEXT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_event_id (event_id),
    INDEX idx_status (status),
    INDEX idx_dr_event_cancellation_effective (execution_status, cancellation_effective_at),
    INDEX idx_dr_event_execution_status (execution_status)
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
