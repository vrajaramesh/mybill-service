-- DEV / TEST DATA ONLY. Not run automatically (not listed in BoutiqueSchemaInitializer.SCRIPTS).
-- Adds sample designs KUR-101..103 with images and customisation options to ONE firm schema.
--
--   psql "$DATABASE_URL" -v schema=firm_srisa -f src/main/resources/db/boutique/dev-seed.sql
--
-- WARNING: active designs are also listed in the public ecom shop (/api/public/{firm}/boutique-designs).
-- Remove them again with the DELETE at the bottom (commented out).
-- Requires V1..V3 to have run (start mybill-service once, or call any /api/agent endpoint).

SET search_path TO :"schema", public;

INSERT INTO boutique_designs (code, name, garment_type, description, fabric_description, rough_price, delivery_days, is_active, created_at, updated_at)
VALUES
    ('KUR-101', 'Anarkali Kurta - Rose',   'Kurta', 'Floor-length anarkali with flared panels.', 'Rayon crepe, rose pink', 1899.00, 7,  TRUE, NOW() - INTERVAL '2 days', NOW()),
    ('KUR-102', 'Straight Kurta - Indigo', 'Kurta', 'Classic straight-cut kurta, side slits.',  'Cotton cambric, indigo block print', 1299.00, 5, TRUE, NOW() - INTERVAL '1 day', NOW()),
    ('KUR-103', 'A-line Kurta - Mustard',  'Kurta', 'A-line kurta with yoke embroidery.',       'Chanderi silk, mustard', 2499.00, 10, TRUE, NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO boutique_design_images (design_id, image_url, sort_order)
SELECT d.design_id, v.url, v.sort_order
  FROM (VALUES
        ('KUR-101', 'https://storage.googleapis.com/mybill-product-images/samples/kur-101-front.jpg', 0),
        ('KUR-101', 'https://storage.googleapis.com/mybill-product-images/samples/kur-101-back.jpg',  1),
        ('KUR-102', 'https://storage.googleapis.com/mybill-product-images/samples/kur-102-front.jpg', 0),
        ('KUR-103', 'https://storage.googleapis.com/mybill-product-images/samples/kur-103-front.jpg', 0)
       ) AS v(code, url, sort_order)
  JOIN boutique_designs d ON d.code = v.code
 WHERE NOT EXISTS (SELECT 1 FROM boutique_design_images i WHERE i.design_id = d.design_id AND i.image_url = v.url);

-- Global options (design_id NULL) apply to every design.
INSERT INTO customisation_options (design_id, option_group, name, price_delta, sort_order)
SELECT NULL, v.grp, v.name, v.delta, v.sort_order
  FROM (VALUES
        ('LENGTH', 'Knee length',  0.00,   1),
        ('LENGTH', 'Calf length',  100.00, 2),
        ('LENGTH', 'Ankle length', 200.00, 3),
        ('SLEEVE', 'Sleeveless',   0.00,   1),
        ('SLEEVE', 'Half sleeve',  0.00,   2),
        ('SLEEVE', 'Full sleeve',  150.00, 3),
        ('LINING', 'No lining',    0.00,   1),
        ('LINING', 'Cotton lining', 250.00, 2)
       ) AS v(grp, name, delta, sort_order)
 WHERE NOT EXISTS (SELECT 1 FROM customisation_options o
                    WHERE o.design_id IS NULL AND o.option_group = v.grp AND o.name = v.name);

-- Design-specific necklines.
INSERT INTO customisation_options (design_id, option_group, name, price_delta, sort_order)
SELECT d.design_id, 'NECK', v.name, v.delta, v.sort_order
  FROM (VALUES
        ('KUR-101', 'Round neck',      0.00,   1),
        ('KUR-101', 'Sweetheart neck', 200.00, 2),
        ('KUR-102', 'Round neck',      0.00,   1),
        ('KUR-102', 'Mandarin collar', 150.00, 2),
        ('KUR-103', 'V neck',          0.00,   1),
        ('KUR-103', 'Boat neck',       100.00, 2)
       ) AS v(code, name, delta, sort_order)
  JOIN boutique_designs d ON d.code = v.code
 WHERE NOT EXISTS (SELECT 1 FROM customisation_options o
                    WHERE o.design_id = d.design_id AND o.option_group = 'NECK' AND o.name = v.name);

-- Clean-up (images have no FK, so delete them first):
-- DELETE FROM boutique_design_images WHERE design_id IN (SELECT design_id FROM boutique_designs WHERE code IN ('KUR-101', 'KUR-102', 'KUR-103'));
-- DELETE FROM boutique_designs WHERE code IN ('KUR-101', 'KUR-102', 'KUR-103');  -- cascades to their options
-- DELETE FROM customisation_options WHERE design_id IS NULL AND option_group IN ('LENGTH', 'SLEEVE', 'LINING');
