-- V5: KHQR payments, confirmation events and refunds (Phase 5)
-- NEVER edit this file after it has run. Add V{n}__*.sql instead.

-- Which gateway took the payment (NULL for cash on delivery) and its own reference for it:
-- for Bakong KHQR the reference is the QR's md5. ABA PayWay (later) fits the same columns.
ALTER TABLE payments
    ADD COLUMN provider         VARCHAR(20),
    ADD COLUMN provider_ref     VARCHAR(100),
    ADD COLUMN redirect_url     VARCHAR(500),
    ADD COLUMN refund_required  BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN refunded_at      TIMESTAMPTZ,
    ADD COLUMN refund_reason    VARCHAR(500),
    ADD COLUMN refunded_by      BIGINT        REFERENCES users (id),
    ADD COLUMN last_checked_at  TIMESTAMPTZ,
    ADD COLUMN check_attempts   INT           NOT NULL DEFAULT 0,
    ADD CONSTRAINT uq_payments_provider_ref UNIQUE (provider, provider_ref),
    ADD CONSTRAINT uq_payments_qr_md5 UNIQUE (qr_md5),
    ADD CONSTRAINT chk_payments_provider CHECK (provider IS NULL OR provider IN ('BAKONG_KHQR'));

UPDATE payments SET provider = 'BAKONG_KHQR' WHERE method = 'KHQR';

-- the check job: pending gateway payments, oldest first
CREATE INDEX idx_payments_pending_check ON payments (status, provider, last_checked_at)
    WHERE provider IS NOT NULL;

-- Every confirmation attempt (check job, webhook, simulation), whatever the outcome. Append-only.
CREATE TABLE payment_events (
    id               BIGSERIAL      PRIMARY KEY,
    payment_id       BIGINT         REFERENCES payments (id),
    source           VARCHAR(20)    NOT NULL,
    provider         VARCHAR(20),
    transaction_ref  VARCHAR(100),
    provider_ref     VARCHAR(100),
    amount           NUMERIC(12, 2),
    currency         VARCHAR(3),
    payload          JSONB,
    signature_valid  BOOLEAN,
    outcome          VARCHAR(20)    NOT NULL,
    received_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payment_events_source CHECK (source IN ('CHECK_JOB', 'WEBHOOK', 'SIMULATED')),
    CONSTRAINT chk_payment_events_outcome CHECK (outcome IN
        ('APPLIED', 'DUPLICATE', 'LATE', 'AMOUNT_MISMATCH', 'UNKNOWN_PAYMENT', 'REJECTED'))
);

CREATE INDEX idx_payment_events_payment_id ON payment_events (payment_id);
CREATE INDEX idx_payment_events_received_at ON payment_events (received_at);
