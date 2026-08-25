CREATE TABLE dr_event_resource
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id            BIGINT NOT NULL,
    openadr_resource_id BIGINT NOT NULL,
    sequence_number     INT    NOT NULL,

    CONSTRAINT fk_dr_event_resource_event
        FOREIGN KEY (event_id) REFERENCES dr_event (id),

    CONSTRAINT fk_dr_event_resource_resource
        FOREIGN KEY (openadr_resource_id) REFERENCES openadr_resource (id),

    CONSTRAINT uk_dr_event_resource_event_resource
        UNIQUE (event_id, openadr_resource_id)
);