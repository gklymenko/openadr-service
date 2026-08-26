ALTER TABLE report_request
    ADD COLUMN delivery_state VARCHAR(32) NOT NULL DEFAULT 'IDLE' AFTER status,
    ADD COLUMN delivery_token VARCHAR(36) NULL AFTER delivery_state,
    ADD COLUMN delivery_claimed_at DATETIME(3) NULL AFTER delivery_token;

CREATE INDEX idx_report_request_delivery
    ON report_request (status, delivery_state, next_report_at);
