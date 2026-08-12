CREATE TABLE openadr_resource (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    charge_point_pk       INT          NOT NULL,
    charge_point_identity VARCHAR(255) NOT NULL,
    charge_point_uuid     VARCHAR(50)  NOT NULL,
    resource_id           VARCHAR(64)  NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    max_power_watts       BIGINT       NULL,
    row_version           BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    CONSTRAINT uk_openadr_resource_charge_point_pk UNIQUE (charge_point_pk),
    CONSTRAINT uk_openadr_resource_charge_point_identity UNIQUE (charge_point_identity),
    CONSTRAINT uk_openadr_resource_charge_point_uuid UNIQUE (charge_point_uuid),
    CONSTRAINT uk_openadr_resource_resource_id UNIQUE (resource_id),
    INDEX idx_openadr_resource_enabled (enabled)
);
