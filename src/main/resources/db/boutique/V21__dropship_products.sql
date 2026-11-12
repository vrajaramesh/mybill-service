-- Dropship variants get their own table instead of rows in Products: they are a supplier's stock, not ours, and must
-- not show up in Products / billing / stock. One row per variant (colour) of a DROPSHIP collection; the collection
-- item points to it (boutique_collection_items.dropship_product_id; product_id stays NULL for dropship items).
-- The supplier is the collection's.
CREATE TABLE IF NOT EXISTS dropship_products (
    dropship_product_id SERIAL        PRIMARY KEY,
    collection_id       INTEGER       REFERENCES boutique_collections (collection_id) ON DELETE CASCADE,
    product_name        VARCHAR(200)  NOT NULL,
    description         TEXT,
    category            VARCHAR(50),               -- free text from the post (saree, suit ...), no Product category needed
    cost_price          NUMERIC(10,2),             -- what the supplier charges
    selling_price       NUMERIC(10,2) NOT NULL DEFAULT 0,
    sizes               VARCHAR(200),              -- "S,M,L,XL" the supplier has; NULL = free size
    tags                TEXT,
    ai_instructions     TEXT,
    image_url           VARCHAR(500),
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
    legacy_product_id   INTEGER,                   -- the Products row it was moved from (V21), removed after the move
    legacy_item_id      INTEGER,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_dropship_products_collection ON dropship_products (collection_id);

ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS dropship_product_id INTEGER
    REFERENCES dropship_products (dropship_product_id) ON DELETE SET NULL;

-- Move the variants of existing DROPSHIP collections out of Products (the Products rows themselves are deleted by
-- BoutiqueSchemaInitializer right after this script, each one only if nothing else uses it).
INSERT INTO dropship_products (collection_id, product_name, description, category, cost_price, selling_price,
                               sizes, tags, ai_instructions, image_url, is_active, legacy_product_id, legacy_item_id, created_at)
SELECT i.collection_id, p.product_name, p.description, LEFT(p.category, 50), p.cost_price,
       COALESCE(p.selling_price, 0),
       (SELECT LEFT(STRING_AGG(s.size_label, ',' ORDER BY s.sort_order, s.size_label), 200)
          FROM product_size_stock s WHERE s.product_id = p.product_id),
       p.tags, p.ai_instructions,
       (SELECT pi.image_url FROM product_images pi WHERE pi.product_id = p.product_id
         ORDER BY COALESCE(pi.sort_order, 0), pi.image_id LIMIT 1),
       COALESCE(p.is_active, TRUE), p.product_id, i.item_id, COALESCE(p.created_at, NOW())
  FROM boutique_collection_items i
  JOIN boutique_collections c ON c.collection_id = i.collection_id
  JOIN products p ON p.product_id = i.product_id
 WHERE c.collection_type = 'DROPSHIP' AND i.dropship_product_id IS NULL;

UPDATE boutique_collection_items i SET dropship_product_id = d.dropship_product_id, product_id = NULL
  FROM dropship_products d WHERE d.legacy_item_id = i.item_id;

-- Who forwarded the post (an owner number): approvals, supplier questions, availability checks and "paid" messages
-- of this collection go to that number only.
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS owner_phone VARCHAR(15);
UPDATE boutique_collections c SET owner_phone = b.from_number
  FROM wa_intake_batch b WHERE b.collection_id = c.collection_id AND c.owner_phone IS NULL;

-- SRISA's two owners forward supplier posts; each is its own user.
INSERT INTO dropship_owner_numbers (phone, label)
SELECT v.phone, v.label FROM (VALUES ('919441249575', 'Owner 9441249575'), ('918008152007', 'Owner 8008152007')) AS v (phone, label)
 WHERE current_schema() = 'firm_srisa'
ON CONFLICT (phone) DO NOTHING;
