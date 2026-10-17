-- Customer identities for WhatsApp / Instagram, and simple size profiles on customer_measurements.

ALTER TABLE customers ADD COLUMN IF NOT EXISTS phone_e164        VARCHAR(16);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS instagram_user_id VARCHAR(64);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS source            VARCHAR(20) NOT NULL DEFAULT 'SHOP';
ALTER TABLE customers ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMP;

ALTER TABLE customers
    ADD CONSTRAINT ck_customers_source CHECK (source IN ('SHOP', 'WHATSAPP', 'INSTAGRAM'));

-- Backfill phone_e164 from the free-text phone, only where the result is unambiguous:
-- Indian mobile numbers (10 digits, or 0/91 prefixed) that no other customer shares.
-- Duplicates stay NULL for manual clean-up (BoutiqueSchemaInitializer logs how many).
WITH digits AS (
    SELECT customer_id, REGEXP_REPLACE(phone, '[^0-9]', '', 'g') AS d
      FROM customers
     WHERE phone IS NOT NULL AND phone_e164 IS NULL
), normalised AS (
    SELECT customer_id,
           CASE
               WHEN LENGTH(d) = 10 AND d ~ '^[6-9]'      THEN '+91' || d
               WHEN LENGTH(d) = 11 AND d ~ '^0[6-9]'     THEN '+91' || SUBSTRING(d FROM 2)
               WHEN LENGTH(d) = 12 AND d ~ '^91[6-9]'    THEN '+' || d
           END AS e164
      FROM digits
), unambiguous AS (
    SELECT e164 FROM normalised WHERE e164 IS NOT NULL GROUP BY e164 HAVING COUNT(*) = 1
)
UPDATE customers c
   SET phone_e164 = n.e164
  FROM normalised n
  JOIN unambiguous u ON u.e164 = n.e164
 WHERE c.customer_id = n.customer_id
   AND NOT EXISTS (SELECT 1 FROM customers x WHERE x.phone_e164 = n.e164);

CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_phone_e164
    ON customers (phone_e164) WHERE phone_e164 IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_instagram_user_id
    ON customers (instagram_user_id) WHERE instagram_user_id IS NOT NULL;

-- Size profile captured by the agent. bust_in maps to the existing "chest" column.
-- History is kept: every save is a new row; the newest (created_at) is the current one.
ALTER TABLE customer_measurements ADD COLUMN IF NOT EXISTS size_code      VARCHAR(5);
ALTER TABLE customer_measurements ADD COLUMN IF NOT EXISTS height_cm      NUMERIC(5,1);
ALTER TABLE customer_measurements ADD COLUMN IF NOT EXISTS fit_preference VARCHAR(10);
ALTER TABLE customer_measurements ADD COLUMN IF NOT EXISTS source         VARCHAR(20) NOT NULL DEFAULT 'SHOP';
ALTER TABLE customer_measurements ADD COLUMN IF NOT EXISTS notes          TEXT;
ALTER TABLE customer_measurements ADD COLUMN IF NOT EXISTS created_at     TIMESTAMP;

ALTER TABLE customer_measurements
    ADD CONSTRAINT ck_customer_measurements_fit CHECK (fit_preference IN ('SNUG', 'REGULAR', 'RELAXED'));

CREATE INDEX IF NOT EXISTS ix_customer_measurements_customer_created
    ON customer_measurements (customer_id, created_at DESC);
