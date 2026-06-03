-- V4: Analyst review audit trail

CREATE TABLE risk_review_audits (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id         UUID         NOT NULL,
    transfer_id      UUID         NOT NULL,
    analyst_user_id  UUID         NOT NULL,
    decision         VARCHAR(20)  NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_decision CHECK (decision IN ('APPROVED', 'REJECTED'))
);

CREATE INDEX idx_review_audit_alert_id    ON risk_review_audits(alert_id);
CREATE INDEX idx_review_audit_transfer_id ON risk_review_audits(transfer_id);
CREATE INDEX idx_review_audit_analyst     ON risk_review_audits(analyst_user_id, created_at);
