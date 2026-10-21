-- Fabric-only orders through the agent: the customer buys metres of a fabric product (products table),
-- no stitching. The item snapshots the product, metres and price per unit; quantity stays 1 and
-- unit_price = line_total = price_per_unit x metres.
ALTER TABLE boutique_orders      ADD COLUMN IF NOT EXISTS order_type            VARCHAR(10) NOT NULL DEFAULT 'GARMENT';
ALTER TABLE boutique_order_items ADD COLUMN IF NOT EXISTS fabric_product_id     INTEGER;
ALTER TABLE boutique_order_items ADD COLUMN IF NOT EXISTS fabric_metres         NUMERIC(8,2);
ALTER TABLE boutique_order_items ADD COLUMN IF NOT EXISTS fabric_unit           VARCHAR(20);
ALTER TABLE boutique_order_items ADD COLUMN IF NOT EXISTS fabric_price_per_unit NUMERIC(10,2);
