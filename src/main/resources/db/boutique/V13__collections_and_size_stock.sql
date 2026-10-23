-- Fabric / readymade collections sold through the WhatsApp assistant, per-size stock for readymade products,
-- and per-product AI instructions.

-- Instructions the assistant follows for this product (e.g. "min 1 m; suggest 2.5 m for a kurti").
ALTER TABLE products ADD COLUMN IF NOT EXISTS ai_instructions TEXT;

-- Readymade garments: each product has its own sizes, with stock per size. When present, the product's
-- stock_quantity is the sum of these rows and products.sizes the list of labels.
CREATE TABLE IF NOT EXISTS product_size_stock (
    product_id INTEGER       NOT NULL,
    size_label VARCHAR(30)   NOT NULL,
    stock      NUMERIC(10,2) NOT NULL DEFAULT 0,
    sort_order INTEGER       NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, size_label),
    CONSTRAINT ck_product_size_stock_stock CHECK (stock >= 0)
);

-- A collection = one Instagram post with several items of the same kind and price (colours / prints).
CREATE TABLE IF NOT EXISTS boutique_collections (
    collection_id   SERIAL       PRIMARY KEY,
    code            VARCHAR(30)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    collection_type VARCHAR(10)  NOT NULL,
    description     TEXT,
    cover_image_url VARCHAR(500),
    ai_instructions TEXT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_boutique_collections_code UNIQUE (code),
    CONSTRAINT ck_boutique_collections_type CHECK (collection_type IN ('FABRIC', 'READYMADE'))
);

-- Items: a product from Products with the code printed on its photo (e.g. MCS-1A) and a colour name.
CREATE TABLE IF NOT EXISTS boutique_collection_items (
    item_id       SERIAL       PRIMARY KEY,
    collection_id INTEGER      NOT NULL REFERENCES boutique_collections (collection_id) ON DELETE CASCADE,
    item_code     VARCHAR(30)  NOT NULL,
    product_id    INTEGER      NOT NULL,
    colour        VARCHAR(80),
    image_url     VARCHAR(500),
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uq_boutique_collection_items_code UNIQUE (item_code)
);
CREATE INDEX IF NOT EXISTS ix_boutique_collection_items_collection ON boutique_collection_items (collection_id);

-- Ad & post links can point at a collection instead of a design (exactly one of the two).
ALTER TABLE marketing_links ADD COLUMN IF NOT EXISTS collection_code VARCHAR(30)
    REFERENCES boutique_collections (code) ON UPDATE CASCADE;
ALTER TABLE marketing_links ALTER COLUMN design_code DROP NOT NULL;
ALTER TABLE marketing_links ADD CONSTRAINT ck_marketing_links_target
    CHECK ((design_code IS NULL) <> (collection_code IS NULL));
