-- Payments bounded context: aggregate storage, idempotency guard, and audit trail.
-- Hibernate runs with ddl-auto=validate, so this file is the single source of truth for the schema.

CREATE TABLE payments (
    id                 UUID           NOT NULL,
    provider           VARCHAR(32)    NOT NULL,
    provider_reference VARCHAR(255),
    authorized_amount  NUMERIC(19, 4) NOT NULL,
    captured_amount    NUMERIC(19, 4) NOT NULL,
    refunded_amount    NUMERIC(19, 4) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    status             VARCHAR(32)    NOT NULL,
    idempotency_key    VARCHAR(255)   NOT NULL,
    failure_reason     VARCHAR(512),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    version            BIGINT         NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id)
);

-- One provider reference belongs to exactly one payment; this is how webhooks are correlated back.
CREATE UNIQUE INDEX ux_payments_provider_reference
    ON payments (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE UNIQUE INDEX ux_payments_idempotency_key ON payments (idempotency_key);

CREATE INDEX ix_payments_status ON payments (status);

-- The primary key is the idempotency key itself, so a duplicate claim is rejected by the database
-- rather than by application code — that is what makes the guard hold under concurrency.
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(255) NOT NULL,
    operation       VARCHAR(32)  NOT NULL,
    payment_id      UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (idempotency_key)
);

CREATE INDEX ix_idempotency_records_payment_id ON idempotency_records (payment_id);

-- Append-only: rows are never updated or deleted.
CREATE TABLE payment_audit_log (
    id          UUID           NOT NULL,
    payment_id  UUID           NOT NULL,
    event_type  VARCHAR(64)    NOT NULL,
    payload     VARCHAR(2048)  NOT NULL,
    occurred_at TIMESTAMPTZ    NOT NULL,
    recorded_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_payment_audit_log PRIMARY KEY (id)
);

CREATE INDEX ix_payment_audit_log_payment_id ON payment_audit_log (payment_id, occurred_at);
