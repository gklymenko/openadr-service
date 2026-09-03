CREATE TABLE openadr_resource (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    ven_key               VARCHAR(64)  NOT NULL,
    charge_point_pk       INT          NOT NULL,
    charge_point_identity VARCHAR(255) NOT NULL,
    resource_id           VARCHAR(64)  NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    row_version           BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    CONSTRAINT uk_openadr_resource_charge_point_pk UNIQUE (charge_point_pk),
    CONSTRAINT uk_openadr_resource_charge_point_identity UNIQUE (charge_point_identity),
    CONSTRAINT uk_openadr_resource_ven_key_resource_id UNIQUE (ven_key, resource_id),
    INDEX idx_openadr_resource_ven_key_enabled (ven_key, enabled)
);
