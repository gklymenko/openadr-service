-- Existing global report capabilities/requests are no longer valid after
-- reportSpecifierID becomes charger-specific.
DELETE FROM report_capability;
UPDATE report_request
SET status = 'CANCELLED'
WHERE report_specifier_id <> 'METADATA';

ALTER TABLE report_capability
    ADD COLUMN resource_id VARCHAR(64) NOT NULL AFTER report_name;

ALTER TABLE report_request
    ADD COLUMN resource_id VARCHAR(64) NULL AFTER report_name;

CREATE TABLE connector_telemetry_state (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id          BIGINT         NOT NULL,
    connector_number     INT            NOT NULL,
    transaction_id       INT            NULL,
    power_kw              DECIMAL(19, 6) NULL,
    power_captured_at     DATETIME(3)    NULL,
    energy_register_kwh   DECIMAL(19, 6) NULL,
    energy_captured_at    DATETIME(3)    NULL,
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    CONSTRAINT fk_connector_telemetry_resource
        FOREIGN KEY (resource_id) REFERENCES openadr_resource (id) ON DELETE CASCADE,
    CONSTRAINT uk_connector_telemetry_resource_connector
        UNIQUE (resource_id, connector_number),
    INDEX idx_connector_telemetry_resource (resource_id)
);

CREATE TABLE resource_telemetry_status (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id         BIGINT      NOT NULL,
    online              BOOLEAN     NOT NULL DEFAULT FALSE,
    status_captured_at  DATETIME(3) NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    CONSTRAINT fk_resource_telemetry_status_resource
        FOREIGN KEY (resource_id) REFERENCES openadr_resource (id) ON DELETE CASCADE,
    CONSTRAINT uk_resource_telemetry_status_resource UNIQUE (resource_id)
);

CREATE TABLE resource_telemetry_sample (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id  BIGINT         NOT NULL,
    captured_at  DATETIME(3)    NOT NULL,
    power_kw      DECIMAL(19, 6) NOT NULL,
    energy_kwh    DECIMAL(19, 6) NOT NULL,
    online       BOOLEAN        NOT NULL,
    created_at   DATETIME(3)    NOT NULL,
    updated_at   DATETIME(3)    NOT NULL,
    CONSTRAINT fk_resource_telemetry_sample_resource
        FOREIGN KEY (resource_id) REFERENCES openadr_resource (id) ON DELETE CASCADE,
    CONSTRAINT uk_resource_telemetry_sample_resource_time
        UNIQUE (resource_id, captured_at),
    INDEX idx_resource_telemetry_sample_lookup (resource_id, captured_at)
);
