-- Razorpay webhook dedupe (public schema: the firm is only known after parsing the event). Idempotent.
CREATE TABLE IF NOT EXISTS public.razorpay_webhook_events (
    event_id     VARCHAR(100) PRIMARY KEY,   -- x-razorpay-event-id
    event_type   VARCHAR(60),
    tenant       VARCHAR(63),
    received_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,                  -- NULL = received but not (successfully) processed yet
    payload      TEXT
);
