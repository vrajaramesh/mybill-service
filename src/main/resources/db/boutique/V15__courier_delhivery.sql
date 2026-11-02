-- Courier (Delhivery): parcel weight for live delivery rates, and the shipment booked for an order.

-- Shipping weight of one unit: grams per metre (fabric) or per piece (readymade / stitched design).
ALTER TABLE products         ADD COLUMN IF NOT EXISTS ship_weight_grams INTEGER;
ALTER TABLE boutique_designs ADD COLUMN IF NOT EXISTS ship_weight_grams INTEGER;

-- Parcel weight (contents + cover) the delivery charge was quoted on.
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS ship_weight_grams INTEGER;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier           VARCHAR(20);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS awb               VARCHAR(40);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_status    VARCHAR(60);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_location  VARCHAR(150);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_status_at TIMESTAMP;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_pickup_id VARCHAR(40);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_cost      NUMERIC(10,2);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_notified  VARCHAR(60);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS shipped_at        TIMESTAMP;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS delivered_at      TIMESTAMP;
CREATE INDEX IF NOT EXISTS ix_boutique_orders_awb ON boutique_orders (awb);
