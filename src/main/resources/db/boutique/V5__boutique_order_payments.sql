-- Razorpay Payment Links for agent boutique orders. (The existing boutique_payments table holds the tailors'
-- cash payments for stitching orders, hence the different name.)

ALTER TABLE boutique_orders DROP CONSTRAINT IF EXISTS ck_boutique_orders_status;
ALTER TABLE boutique_orders ADD CONSTRAINT ck_boutique_orders_status CHECK (status IN
    ('DRAFT', 'PAYMENT_PENDING', 'PAID', 'IN_STITCHING', 'DISPATCHED', 'DELIVERED', 'CANCELLED'));
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS boutique_order_payments (
    payment_id               SERIAL       PRIMARY KEY,
    order_id                 INTEGER      NOT NULL REFERENCES boutique_orders (order_id) ON DELETE CASCADE,
    razorpay_payment_link_id VARCHAR(64),
    reference_id             VARCHAR(40)  NOT NULL,
    short_url                VARCHAR(255),
    amount_paise             BIGINT       NOT NULL,
    currency                 VARCHAR(3)   NOT NULL DEFAULT 'INR',
    status                   VARCHAR(20)  NOT NULL DEFAULT 'created',
    razorpay_payment_id      VARCHAR(64),
    paid_at                  TIMESTAMP,
    expires_at               TIMESTAMP    NOT NULL,
    -- Set when Razorpay reports a different amount/currency than we asked for; never marked paid automatically.
    needs_review             BOOLEAN      NOT NULL DEFAULT FALSE,
    review_reason            TEXT,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_boutique_order_payments_link UNIQUE (razorpay_payment_link_id),
    CONSTRAINT uq_boutique_order_payments_reference UNIQUE (reference_id),
    CONSTRAINT ck_boutique_order_payments_status CHECK (status IN ('created', 'paid', 'expired', 'cancelled'))
);
CREATE INDEX IF NOT EXISTS ix_boutique_order_payments_order ON boutique_order_payments (order_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_boutique_order_payments_status ON boutique_order_payments (status, created_at);
