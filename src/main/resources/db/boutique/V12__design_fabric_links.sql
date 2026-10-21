-- Fabrics sold separately for a design: links to product pages on the shop website
-- (e.g. https://srisafabrics.com/?product=6223). The WhatsApp assistant sends them when a customer wants
-- only the fabric; she buys on the website. product_id is parsed from the link (null if it has none).
CREATE TABLE IF NOT EXISTS boutique_design_fabrics (
    id         SERIAL       PRIMARY KEY,
    design_id  INTEGER      NOT NULL REFERENCES boutique_designs (design_id) ON DELETE CASCADE,
    url        VARCHAR(500) NOT NULL,
    product_id INTEGER,
    label      VARCHAR(150),
    sort_order INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_boutique_design_fabrics UNIQUE (design_id, url)
);
CREATE INDEX IF NOT EXISTS ix_boutique_design_fabrics_design ON boutique_design_fabrics (design_id);

-- The single fabric link of V1 becomes the first website link.
INSERT INTO boutique_design_fabrics (design_id, url, product_id)
SELECT design_id, 'https://srisafabrics.com/?product=' || fabric_product_id, fabric_product_id
  FROM boutique_designs
 WHERE fabric_product_id IS NOT NULL
ON CONFLICT (design_id, url) DO NOTHING;
