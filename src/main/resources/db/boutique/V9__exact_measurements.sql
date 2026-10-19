-- Designs that need the customer's exact measurements (blouse/choli, lehenga & choli, ...): after payment our
-- tailor calls the customer. The order tracks that call (PENDING_CALL -> TAKEN, by whom and when).
ALTER TABLE boutique_designs ADD COLUMN IF NOT EXISTS exact_measurements BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS measurement_status   VARCHAR(20);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS measurement_taken_at TIMESTAMP;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS measurement_taken_by VARCHAR(100);
ALTER TABLE boutique_orders ADD CONSTRAINT ck_boutique_orders_measurement_status
    CHECK (measurement_status IS NULL OR measurement_status IN ('PENDING_CALL', 'TAKEN'));
CREATE INDEX IF NOT EXISTS ix_boutique_orders_measurement_pending
    ON boutique_orders (paid_at) WHERE measurement_status = 'PENDING_CALL';
