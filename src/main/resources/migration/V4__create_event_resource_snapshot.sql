CREATE TABLE dr_event_resource
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT      NOT NULL,
    resource_id     VARCHAR(64) NOT NULL,
    sequence_number INT         NOT NULL,

    CONSTRAINT fk_dr_event_resource_event
        FOREIGN KEY (event_id)
            REFERENCES dr_event (id)
            ON DELETE CASCADE,

    CONSTRAINT uk_dr_event_resource_event_resource_id
        UNIQUE (event_id, resource_id)
);