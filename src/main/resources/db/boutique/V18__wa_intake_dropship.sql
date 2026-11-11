-- WhatsApp → Dropship collection automation (phase 1: data).
-- The owner forwards a supplier's post (one text + many photos, one colour per photo) to the shop's WhatsApp number;
-- srisa-agent stores each message in wa_intake_message (idempotent by wa_message_id) and groups them into a
-- wa_intake_batch. mybill builds one DRAFT dropship collection from a batch (one variant = one Products row + item per
-- photo), the owner approves it on WhatsApp, and it is published (Instagram posts in marketing_links, Meta catalog).
-- Supplier, collection, items, courier slabs and the customer's chat state already exist (V13/V16, srisa-agent).

-- Collections: lifecycle (DRAFT until approved; is_active stays the "assistant sells it" flag = status ACTIVE),
-- where it came from, and whether the supplier's shipping is charged on top ("PRICE- 1650+ SHIPPING").
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS status VARCHAR(10);
UPDATE boutique_collections SET status = CASE WHEN is_active THEN 'ACTIVE' ELSE 'ARCHIVED' END WHERE status IS NULL;
ALTER TABLE boutique_collections ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE boutique_collections ALTER COLUMN status SET NOT NULL;
ALTER TABLE boutique_collections DROP CONSTRAINT IF EXISTS ck_boutique_collections_status;
ALTER TABLE boutique_collections ADD CONSTRAINT ck_boutique_collections_status
    CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE boutique_collections DROP CONSTRAINT IF EXISTS ck_boutique_collections_source;
ALTER TABLE boutique_collections ADD CONSTRAINT ck_boutique_collections_source
    CHECK (source IN ('MANUAL', 'WHATSAPP_FORWARD'));
ALTER TABLE boutique_collections ADD COLUMN IF NOT EXISTS shipping_extra BOOLEAN NOT NULL DEFAULT TRUE;

-- Suppliers: default courier charges per zone, copied into each collection built from their posts.
ALTER TABLE boutique_suppliers ADD COLUMN IF NOT EXISTS default_courier_slabs TEXT;
ALTER TABLE boutique_suppliers ADD COLUMN IF NOT EXISTS default_courier_extra_per_piece NUMERIC(10,2);

-- Items (variants): number within the post, extra text (e.g. the blouse), the original and the code-stamped photo,
-- a 64-bit perceptual hash (hex) to spot a photo posted before, and availability (SOLD_OUT = not offered).
ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS variant_no          INTEGER;
ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS secondary_desc      TEXT;
ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS original_image_path VARCHAR(500);
ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS stamped_image_url   VARCHAR(500);
ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS image_phash         VARCHAR(16);
ALTER TABLE boutique_collection_items ADD COLUMN IF NOT EXISTS status VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE boutique_collection_items DROP CONSTRAINT IF EXISTS ck_boutique_collection_items_status;
ALTER TABLE boutique_collection_items ADD CONSTRAINT ck_boutique_collection_items_status
    CHECK (status IN ('AVAILABLE', 'SOLD_OUT'));
CREATE INDEX IF NOT EXISTS ix_boutique_collection_items_phash ON boutique_collection_items (image_phash);

-- One forwarded post: messages arrive one by one and are collected until the batch is closed.
CREATE TABLE IF NOT EXISTS wa_intake_batch (
    batch_id      SERIAL       PRIMARY KEY,
    from_number   VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'COLLECTING',
    collection_id INTEGER      REFERENCES boutique_collections (collection_id) ON DELETE SET NULL,
    supplier_id   INTEGER      REFERENCES boutique_suppliers (supplier_id),
    extracted     TEXT,                       -- JSON the AI read from the post (name, model, prices, colours...)
    error         TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    closed_at     TIMESTAMP,
    CONSTRAINT ck_wa_intake_batch_status CHECK (status IN ('COLLECTING', 'AWAITING_SUPPLIER', 'EXTRACTING',
        'AWAITING_APPROVAL', 'APPROVED', 'PUBLISHED', 'SKIPPED', 'FAILED'))
);
-- At most one batch collecting per sender.
CREATE UNIQUE INDEX IF NOT EXISTS ux_wa_intake_batch_collecting ON wa_intake_batch (from_number) WHERE status = 'COLLECTING';

CREATE TABLE IF NOT EXISTS wa_intake_message (
    message_id    SERIAL       PRIMARY KEY,
    wa_message_id VARCHAR(100) NOT NULL UNIQUE,   -- WhatsApp's id: a redelivered webhook is ignored
    batch_id      INTEGER      REFERENCES wa_intake_batch (batch_id) ON DELETE CASCADE,
    from_number   VARCHAR(20)  NOT NULL,
    msg_type      VARCHAR(10)  NOT NULL,
    text          TEXT,                            -- message text or photo caption
    media_id      VARCHAR(100),
    mime_type     VARCHAR(50),
    gcs_path      VARCHAR(500),
    received_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    processed     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_wa_intake_message_type CHECK (msg_type IN ('text', 'image'))
);
CREATE INDEX IF NOT EXISTS ix_wa_intake_message_batch ON wa_intake_message (batch_id, received_at);
