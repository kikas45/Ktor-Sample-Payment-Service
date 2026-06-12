-- For now we have V1__create_schema.sql
-- The Core payment system schema

CREATE TABLE IF NOT EXISTS merchants (
    id                  SERIAL          PRIMARY KEY,
    business_name       VARCHAR(255)    NOT NULL,
    email               VARCHAR(255)    NOT NULL UNIQUE,
    settlement_account  VARCHAR(255)    NOT NULL,
    status              VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transactions (
    id                   SERIAL          PRIMARY KEY,
    amount               NUMERIC(15, 2)  NOT NULL,
    currency             VARCHAR(10)     NOT NULL DEFAULT 'NGN',
    status               VARCHAR(50)     NOT NULL DEFAULT 'INITIATED',
    merchant_ref         VARCHAR(255)    NOT NULL,
    internal_ref         VARCHAR(255)    NOT NULL UNIQUE,
    fee                  NUMERIC(15, 2)  NOT NULL,
    merchant_id          INT             NOT NULL REFERENCES merchants(id),
    settlement_batch_id  INT,
    idempotency_key      VARCHAR(255)    UNIQUE,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS settlement_batches (
    id              SERIAL          PRIMARY KEY,
    batch_ref       VARCHAR(255)    NOT NULL UNIQUE,
    merchant_id     INT             NOT NULL REFERENCES merchants(id),
    total_amount    NUMERIC(15, 2)  NOT NULL,
    fee_deducted    NUMERIC(15, 2)  NOT NULL DEFAULT 0,
    tx_count        INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Add FK for settlement_batch_id after the batch table exists
ALTER TABLE transactions
    ADD CONSTRAINT fk_settlement_batch
    FOREIGN KEY (settlement_batch_id)
    REFERENCES settlement_batches(id);

-- Indexes for common query patterns
CREATE INDEX idx_transactions_merchant_id   ON transactions(merchant_id);
CREATE INDEX idx_transactions_status        ON transactions(status);
CREATE INDEX idx_transactions_created_at    ON transactions(created_at);
CREATE INDEX idx_transactions_idempotency   ON transactions(idempotency_key);
CREATE INDEX idx_settlement_batches_merchant ON settlement_batches(merchant_id);
