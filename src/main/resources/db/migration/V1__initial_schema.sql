-- V1: Core ledger schema
-- Money stored in minor units (cents). No floating-point currency columns.

CREATE TABLE accounts (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id           UUID        NOT NULL,
    account_number          VARCHAR(50) NOT NULL,
    currency VARCHAR(3)     NOT NULL DEFAULT 'USD',
    available_balance_minor BIGINT      NOT NULL DEFAULT 0,
    held_balance_minor      BIGINT      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_available_non_negative CHECK (available_balance_minor >= 0),
    CONSTRAINT chk_held_non_negative      CHECK (held_balance_minor      >= 0)
);

CREATE UNIQUE INDEX idx_accounts_account_number ON accounts(account_number);
CREATE INDEX        idx_accounts_owner          ON accounts(owner_user_id);

-- -----------------------------------------------------------------------

CREATE TABLE beneficiaries (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id          UUID        NOT NULL,
    destination_account_id UUID        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_beneficiaries_owner_dest
    ON beneficiaries(owner_user_id, destination_account_id);

-- -----------------------------------------------------------------------

CREATE TABLE transfers (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id             VARCHAR(255) NOT NULL,
    source_account_id      UUID        NOT NULL,
    destination_account_id UUID        NOT NULL,
    owner_user_id          UUID        NOT NULL,
    amount_minor           BIGINT      NOT NULL,
    currency VARCHAR(3)     NOT NULL,
    status                 VARCHAR(50) NOT NULL,
    risk_score             INT         NOT NULL DEFAULT 0,
    risk_level             VARCHAR(20),
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    version                BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_amount_positive CHECK (amount_minor > 0)
);

CREATE UNIQUE INDEX idx_transfers_request_id    ON transfers(request_id);
CREATE INDEX        idx_transfers_source_account ON transfers(source_account_id);
CREATE INDEX        idx_transfers_owner          ON transfers(owner_user_id);
CREATE INDEX        idx_transfers_status         ON transfers(status);

-- -----------------------------------------------------------------------

CREATE TABLE ledger_entries (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id  UUID        NOT NULL,
    account_id   UUID        NOT NULL,
    entry_type   VARCHAR(50) NOT NULL,
    amount_minor BIGINT      NOT NULL,
    currency VARCHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    description  VARCHAR(255),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX idx_ledger_entries_transfer_id ON ledger_entries(transfer_id);
CREATE INDEX idx_ledger_entries_account_id  ON ledger_entries(account_id);

-- -----------------------------------------------------------------------

CREATE TABLE login_audits (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID        NOT NULL,
    ip_address_hash          VARCHAR(255),
    device_fingerprint_hash  VARCHAR(255),
    anomaly_flag             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_login_audits_user_created ON login_audits(user_id, created_at);
CREATE INDEX idx_login_audits_anomaly      ON login_audits(user_id, anomaly_flag, created_at);
