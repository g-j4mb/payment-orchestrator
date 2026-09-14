-- Backend-owned idempotency and crash-safe pending states for authorize/refund reconciliation.
-- See Payment.markAuthorizationPending / beginRefundAttempt and ReconcilePendingPaymentsService.
--
-- payment_method_token has no sensible backfill default — this assumes a fresh database (true for
-- the docker-compose/Testcontainers setups this project actually runs against). Applying it to an
-- environment with pre-existing payment rows would need a backfill first.

ALTER TABLE payments
    ADD COLUMN payment_method_token         VARCHAR(255)   NOT NULL,
    ADD COLUMN capture_mode                 VARCHAR(16)    NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN reconciliation_attempts      INT            NOT NULL DEFAULT 0,
    ADD COLUMN pending_since               TIMESTAMPTZ,
    ADD COLUMN pending_refund_amount        NUMERIC(19, 4),
    ADD COLUMN pending_refund_reason        VARCHAR(512),
    ADD COLUMN refund_attempt_idempotency_key VARCHAR(255);

-- The reconciliation job scans for AUTHORIZATION_PENDING / REFUND_PENDING rows past an age
-- threshold; a composite index on (status, updated_at) is what that query actually needs, on top
-- of the existing single-column ix_payments_status.
CREATE INDEX ix_payments_status_updated_at ON payments (status, updated_at);
