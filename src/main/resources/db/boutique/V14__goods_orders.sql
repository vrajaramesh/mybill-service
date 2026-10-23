-- Multi-line goods orders through the WhatsApp assistant: fabric by the metre and readymade garments by size,
-- usually items of a collection (e.g. MCS-1B 2.5 m + KUR-3A size M x 2). order_type FABRIC / READYMADE / MIXED.
-- Readymade sizes are free text per product (up to 30 characters, see product_size_stock).
ALTER TABLE boutique_order_items ALTER COLUMN size_code TYPE VARCHAR(30);
-- GARMENT (stitched design), FABRIC (metres in fabric_metres) or READYMADE (pieces in quantity, size in size_code).
ALTER TABLE boutique_order_items ADD COLUMN IF NOT EXISTS item_kind VARCHAR(10);
-- The collection item code printed on the photo (e.g. MCS-1B), when ordered from a collection.
ALTER TABLE boutique_order_items ADD COLUMN IF NOT EXISTS item_code VARCHAR(30);
-- Set when a paid goods order reduced Products stock (done once).
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS stock_reduced_at TIMESTAMP;
