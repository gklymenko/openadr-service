ALTER TABLE openadr_resource
    ADD COLUMN ven_key VARCHAR(64) NULL AFTER id;

UPDATE openadr_resource
SET ven_key = 'primary'
WHERE ven_key IS NULL;

ALTER TABLE openadr_resource
    MODIFY COLUMN ven_key VARCHAR(64) NOT NULL,
    DROP INDEX uk_openadr_resource_resource_id,
    ADD CONSTRAINT uk_openadr_resource_ven_key_resource_id
        UNIQUE (ven_key, resource_id),
    ADD INDEX idx_openadr_resource_ven_key_enabled (ven_key, enabled);
