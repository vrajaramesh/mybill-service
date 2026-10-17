-- Boutique catalog for the AI agent: design codes, customisation options, size chart.
-- Runs once per firm schema (search_path = firm schema). Never edit after release; add V<n+1>.

-- Normally created by FirmService; kept here so the script also works on an older schema.
CREATE TABLE IF NOT EXISTS boutique_designs (
    design_id     SERIAL        PRIMARY KEY,
    garment_type  VARCHAR(100)  NOT NULL,
    description   TEXT,
    rough_price   NUMERIC(10,2),
    delivery_days INTEGER,
    image_url     VARCHAR(500),
    public_id     VARCHAR(255),
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     DEFAULT NOW(),
    updated_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS boutique_design_images (
    id         SERIAL       PRIMARY KEY,
    design_id  INTEGER      NOT NULL,
    image_url  VARCHAR(500) NOT NULL,
    public_id  VARCHAR(255),
    created_at TIMESTAMP    DEFAULT NOW()
);

-- rough_price is the design's base price and delivery_days its lead time (exposed as such by /api/agent).
ALTER TABLE boutique_designs ADD COLUMN IF NOT EXISTS code               VARCHAR(30);
ALTER TABLE boutique_designs ADD COLUMN IF NOT EXISTS name               VARCHAR(150);
ALTER TABLE boutique_designs ADD COLUMN IF NOT EXISTS fabric_description TEXT;
ALTER TABLE boutique_designs ADD COLUMN IF NOT EXISTS fabric_product_id  INTEGER;

-- Existing designs get a code from their garment type, e.g. "Lehenga" #6 -> LEH-6 (editable later).
UPDATE boutique_designs
   SET code = COALESCE(NULLIF(UPPER(LEFT(REGEXP_REPLACE(garment_type, '[^A-Za-z]', '', 'g'), 3)), ''), 'BD')
              || '-' || design_id
 WHERE code IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_boutique_designs_code ON boutique_designs (code);

ALTER TABLE boutique_designs
    ADD CONSTRAINT fk_boutique_designs_fabric_product
    FOREIGN KEY (fabric_product_id) REFERENCES products (product_id) ON DELETE SET NULL;

ALTER TABLE boutique_design_images ADD COLUMN IF NOT EXISTS sort_order INTEGER NOT NULL DEFAULT 0;

-- design_id NULL = the option applies to every design.
CREATE TABLE IF NOT EXISTS customisation_options (
    option_id    SERIAL        PRIMARY KEY,
    design_id    INTEGER       REFERENCES boutique_designs (design_id) ON DELETE CASCADE,
    option_group VARCHAR(30)   NOT NULL,
    name         VARCHAR(100)  NOT NULL,
    price_delta  NUMERIC(10,2) NOT NULL DEFAULT 0,
    is_active    BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order   INTEGER       NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customisation_options_group CHECK (option_group ~ '^[A-Z_]{2,30}$')
);
CREATE INDEX IF NOT EXISTS ix_customisation_options_design ON customisation_options (design_id);

CREATE TABLE IF NOT EXISTS size_chart (
    size_id     SERIAL       PRIMARY KEY,
    size_code   VARCHAR(5)   NOT NULL,
    bust_in     NUMERIC(4,1) NOT NULL,
    waist_in    NUMERIC(4,1) NOT NULL,
    hip_in      NUMERIC(4,1) NOT NULL,
    shoulder_in NUMERIC(4,1) NOT NULL,
    length_in   NUMERIC(4,1) NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uq_size_chart_code UNIQUE (size_code)
);

-- Standard women's kurta chart (body measurements, inches). Only seeded into an empty table.
INSERT INTO size_chart (size_code, bust_in, waist_in, hip_in, shoulder_in, length_in, sort_order)
SELECT v.* FROM (VALUES
    ('XS',  32.0, 26.0, 35.0, 13.5, 44.0, 1),
    ('S',   34.0, 28.0, 37.0, 14.0, 44.0, 2),
    ('M',   36.0, 30.0, 39.0, 14.5, 45.0, 3),
    ('L',   38.0, 32.0, 41.0, 15.0, 45.0, 4),
    ('XL',  40.0, 34.0, 43.0, 15.5, 46.0, 5),
    ('XXL', 42.0, 36.0, 45.0, 16.0, 46.0, 6)
) AS v(size_code, bust_in, waist_in, hip_in, shoulder_in, length_in, sort_order)
WHERE NOT EXISTS (SELECT 1 FROM size_chart);
