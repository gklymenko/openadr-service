ALTER TABLE dr_event
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN vtn_status VARCHAR(32) NULL,
    ADD COLUMN execution_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    ADD COLUMN requested_start_time DATETIME(3) NULL,
    ADD COLUMN start_after_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN random_offset_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN ramp_up_seconds BIGINT NULL,
    ADD COLUMN recovery_seconds BIGINT NULL,
    ADD COLUMN last_applied_interval INT NOT NULL DEFAULT -1,
    ADD COLUMN applied_at DATETIME(3) NULL,
    ADD COLUMN completed_at DATETIME(3) NULL;

UPDATE dr_event
SET vtn_status = status,
    requested_start_time = start_time,
    execution_status = CASE
        WHEN status = 'CANCELLED' THEN 'CANCELLED'
        WHEN status = 'COMPLETED' THEN 'COMPLETED'
        WHEN opt_type = 'OPT_IN' THEN 'SCHEDULED'
        ELSE 'RECEIVED'
    END;

ALTER TABLE dr_event
    MODIFY COLUMN vtn_status VARCHAR(32) NOT NULL,
    MODIFY COLUMN requested_start_time DATETIME(3) NOT NULL;

ALTER TABLE dr_event_signal
    ADD COLUMN selected_for_execution BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_dr_event_execution_status ON dr_event (execution_status);
