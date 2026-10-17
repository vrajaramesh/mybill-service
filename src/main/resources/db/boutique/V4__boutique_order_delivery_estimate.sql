-- Estimated delivery date fixed at order time (lead time + transit days), so it doesn't move when viewed later.
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS estimated_delivery_date DATE;
CREATE INDEX IF NOT EXISTS ix_boutique_orders_status_created ON boutique_orders (status, created_at DESC);
