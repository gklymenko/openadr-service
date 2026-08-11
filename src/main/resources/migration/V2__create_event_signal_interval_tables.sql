CREATE TABLE dr_event_signal (
                                 id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                                 event_id          BIGINT         NOT NULL,
                                 sequence_number   INT            NOT NULL,
                                 signal_id         VARCHAR(128)   NOT NULL,
                                 signal_name       VARCHAR(64)    NOT NULL,
                                 signal_type       VARCHAR(64)    NOT NULL,
                                 current_value     DECIMAL(19, 6) NULL,
                                 item_base_element VARCHAR(128)   NULL,
                                 item_base_type    VARCHAR(128)   NULL,
                                 item_units        VARCHAR(64)    NULL,
                                 si_scale_code     VARCHAR(32)    NULL,
                                 CONSTRAINT fk_dr_event_signal_event
                                     FOREIGN KEY (event_id) REFERENCES dr_event (id),
                                 CONSTRAINT uk_dr_event_signal_event_signal_id
                                     UNIQUE (event_id, signal_id),
                                 INDEX idx_dr_event_signal_event (event_id)
);

CREATE TABLE dr_event_interval (
                                   id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   signal_id        BIGINT         NOT NULL,
                                   sequence_number  INT            NOT NULL,
                                   interval_uid     VARCHAR(64)    NOT NULL,
                                   duration_seconds BIGINT         NOT NULL,
                                   payload_value    DECIMAL(19, 6) NOT NULL,
                                   CONSTRAINT fk_dr_event_interval_signal
                                       FOREIGN KEY (signal_id) REFERENCES dr_event_signal (id),
                                   CONSTRAINT uk_dr_event_interval_signal_uid
                                       UNIQUE (signal_id, interval_uid),
                                   INDEX idx_dr_event_interval_signal (signal_id)
);