ALTER TABLE dr_event
    ADD COLUMN cancellation_type VARCHAR(16) NULL AFTER completed_at,
    ADD COLUMN cancellation_requested_at DATETIME(3) NULL AFTER cancellation_type,
    ADD COLUMN cancellation_effective_at DATETIME(3) NULL AFTER cancellation_requested_at;

CREATE INDEX idx_dr_event_cancellation_effective
    ON dr_event (execution_status, cancellation_effective_at);
