-- Wholesale collections: one Instagram post of a wholesale product with its designs as items (e.g. "Madras checked
-- slub cotton" with MCS-1, MCS-2, MCS-3). Items point to a product of the Wholesale module (wholesale_products), which
-- gives the price slabs, minimum order, GST %, stock and the owner's AI instructions. The assistant sells them like a
-- fabric collection (codes + quantities in a cart); the slab and the minimum order apply to the collection's total
-- quantity. When the buyer confirms, a DRAFT order of type WHOLESALE is saved here and a quotation draft is created in
-- the Wholesale module for the team to review and send (no payment link).

ALTER TABLE boutique_collections DROP CONSTRAINT IF EXISTS ck_boutique_collections_type;
ALTER TABLE boutique_collections ADD CONSTRAINT ck_boutique_collections_type
    CHECK (collection_type IN ('FABRIC', 'READYMADE', 'DROPSHIP', 'WHOLESALE'));

ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS wholesale_product_id INTEGER;
ALTER TABLE boutique_collection_items ALTER COLUMN product_id DROP NOT NULL;

ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS gst_amount             NUMERIC(12,2);   -- wholesale: GST estimate
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS wholesale_customer_id  INTEGER;
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS wholesale_quotation_id INTEGER;
