CREATE TABLE report_capability (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_specifier_id         VARCHAR(64) NOT NULL,
    report_name                 VARCHAR(64) NOT NULL,
    resource_id                 VARCHAR(64) NOT NULL,
    supported_rids              VARCHAR(255) NOT NULL,
    min_sampling_period_seconds BIGINT NOT NULL,
    max_sampling_period_seconds BIGINT NOT NULL,
    available_duration_seconds  BIGINT NOT NULL,
    created_at                  DATETIME(3) NOT NULL,
    updated_at                  DATETIME(3) NOT NULL,
    CONSTRAINT uk_report_capability_specifier UNIQUE (report_specifier_id)
);

CREATE TABLE report_request (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_request_id            VARCHAR(64) NOT NULL,
    report_specifier_id          VARCHAR(64) NOT NULL,
    report_name                  VARCHAR(64) NOT NULL,
    resource_id                  VARCHAR(64) NULL,
    requested_rids               VARCHAR(255) NOT NULL,
    granularity_seconds          BIGINT NOT NULL,
    report_back_duration_seconds BIGINT NOT NULL,
    requested_start              DATETIME(3) NULL,
    requested_duration_seconds   BIGINT NULL,
    next_report_at               DATETIME(3) NULL,
    last_reported_at             DATETIME(3) NULL,
    status                       VARCHAR(32) NOT NULL,
    created_at                   DATETIME(3) NOT NULL,
    updated_at                   DATETIME(3) NOT NULL,
    CONSTRAINT uk_report_request_id UNIQUE (report_request_id),
    INDEX idx_report_request_status (status),
    INDEX idx_report_request_due (status, next_report_at),
    INDEX idx_report_request_specifier (report_specifier_id)
);

DROP TABLE IF EXISTS ven_report;
