-- Instagram support: customer merges (phone + Instagram id seen together), WhatsApp updates consent,
-- and IG_MEDIA marketing links synced from Instagram posts (permalink, created_by).

-- Audit of customers merged by the agent upsert. merged_customer is the deleted row as it was.
CREATE TABLE IF NOT EXISTS customer_merges (
    merge_id           SERIAL      PRIMARY KEY,
    kept_customer_id   INTEGER     NOT NULL,
    merged_customer_id INTEGER     NOT NULL,
    phone_e164         VARCHAR(16),
    instagram_user_id  VARCHAR(64),
    moved              JSONB       NOT NULL,
    merged_customer    JSONB       NOT NULL,
    reason             VARCHAR(100) NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_customer_merges_kept ON customer_merges (kept_customer_id);

-- NULL = never asked.
ALTER TABLE customers ADD COLUMN IF NOT EXISTS whatsapp_updates_consent BOOLEAN;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS consent_at               TIMESTAMP;

ALTER TABLE marketing_links ADD COLUMN IF NOT EXISTS permalink  VARCHAR(500);
ALTER TABLE marketing_links ADD COLUMN IF NOT EXISTS created_by VARCHAR(10) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE marketing_links ADD CONSTRAINT ck_marketing_links_created_by CHECK (created_by IN ('MANUAL', 'AUTO'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_marketing_links_permalink
    ON marketing_links (source_type, permalink) WHERE permalink IS NOT NULL;
