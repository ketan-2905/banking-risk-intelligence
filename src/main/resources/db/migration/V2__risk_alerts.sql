-- V2: Risk alert table for fraud triage

CREATE TABLE risk_alerts (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id           UUID        NOT NULL,
    owner_user_id         UUID        NOT NULL,
    risk_score            INT         NOT NULL,
    risk_level            VARCHAR(20) NOT NULL,
    triggered_rules_json  TEXT        NOT NULL DEFAULT '[]',
    context_snapshot_json TEXT        NOT NULL DEFAULT '{}',
    masked_narrative      TEXT,
    status                VARCHAR(50) NOT NULL,
    priority              BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_risk_score_range CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_risk_alerts_transfer_id    ON risk_alerts(transfer_id);
CREATE INDEX idx_risk_alerts_status_created ON risk_alerts(status, created_at);
CREATE INDEX idx_risk_alerts_risk_level     ON risk_alerts(risk_level);
CREATE INDEX idx_risk_alerts_owner_status   ON risk_alerts(owner_user_id, status);
