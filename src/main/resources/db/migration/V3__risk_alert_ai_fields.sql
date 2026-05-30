-- V3: AI narrative fields for risk alerts

ALTER TABLE risk_alerts
    ADD COLUMN ai_error_code    VARCHAR(100),
    ADD COLUMN ai_attempt_count INT         NOT NULL DEFAULT 0,
    ADD COLUMN ai_completed_at  TIMESTAMPTZ;
