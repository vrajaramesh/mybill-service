-- Dropshipping: products a supplier stocks and ships to our customer. We post them as a DROPSHIP collection, the
-- assistant sells them like readymade goods (no stock check), and after payment staff send the order to the supplier,
-- pay them per order (cost + their courier charge) and enter the tracking number the supplier gives.

CREATE TABLE IF NOT EXISTS boutique_suppliers (
    supplier_id     SERIAL       PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,
    contact_person  VARCHAR(100),
    phone           VARCHAR(20),               -- WhatsApp number the order is sent to
    address         TEXT,
    pincode         VARCHAR(6),                -- where they ship from: decides the courier slab (zone)
    payment_details TEXT,                      -- UPI id / bank account, shown to staff when paying an order
    dispatch_days   INTEGER      NOT NULL DEFAULT 2,
    notes           TEXT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

ALTER TABLE boutique_collections DROP CONSTRAINT IF EXISTS ck_boutique_collections_type;
ALTER TABLE boutique_collections ADD CONSTRAINT ck_boutique_collections_type
    CHECK (collection_type IN ('FABRIC', 'READYMADE', 'DROPSHIP'));
-- DROPSHIP collections: the supplier and their courier charge per zone (supplier pincode -> customer pincode),
-- JSON {"LOCAL":60,"STATE":80,"REGION":100,"NATIONAL":120,"SPECIAL":180}, plus a charge per extra piece.
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS supplier_id INTEGER REFERENCES boutique_suppliers (supplier_id);
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS courier_slabs TEXT;
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS courier_extra_per_piece NUMERIC(10,2);

-- Dropship orders (order_type DROPSHIP): what we owe the supplier and how far it got.
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_id          INTEGER;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_name        VARCHAR(150);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_cost        NUMERIC(10,2);   -- products' cost price x qty
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_courier     NUMERIC(10,2);   -- their courier charge (= our delivery charge)
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_zone        VARCHAR(10);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_sent_at     TIMESTAMP;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_paid_at     TIMESTAMP;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_paid_ref    VARCHAR(100);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS supplier_paid_by     VARCHAR(100);
-- Tracking link of a courier other than Delhivery (the supplier's courier).
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS courier_tracking_url VARCHAR(500);
ALTER TABLE boutique_orders ALTER COLUMN courier TYPE VARCHAR(40);
