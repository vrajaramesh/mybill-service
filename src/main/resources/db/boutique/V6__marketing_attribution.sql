-- Marketing attribution: Click-to-WhatsApp ads (AD) and, later, Instagram posts (IG_MEDIA).
-- marketing_links maps an ad / post id to the design it promotes; the agent resolves it when a conversation starts
-- from an ad, tags the order with the source and reports conversation_started events for the "Ad performance" report.

CREATE TABLE IF NOT EXISTS marketing_links (
    link_id     SERIAL       PRIMARY KEY,
    source_type VARCHAR(20)  NOT NULL,
    source_id   VARCHAR(40)  NOT NULL,
    design_code VARCHAR(30)  NOT NULL REFERENCES boutique_designs (code) ON UPDATE CASCADE,
    label       VARCHAR(150) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_marketing_links_source UNIQUE (source_type, source_id),
    CONSTRAINT ck_marketing_links_type CHECK (source_type IN ('AD', 'IG_MEDIA')),
    CONSTRAINT ck_marketing_links_source_id CHECK (source_id ~ '^[0-9]{1,40}$')
);

-- Set by the agent when the conversation came from an ad/post (NULL = organic).
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS source_type VARCHAR(20);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS source_id   VARCHAR(40);
ALTER TABLE boutique_orders ADD COLUMN IF NOT EXISTS ctwa_clid   VARCHAR(500);
ALTER TABLE boutique_orders ADD CONSTRAINT ck_boutique_orders_source_type
    CHECK (source_type IS NULL OR source_type IN ('AD', 'IG_MEDIA'));
CREATE INDEX IF NOT EXISTS ix_boutique_orders_source ON boutique_orders (source_type, source_id);

CREATE TABLE IF NOT EXISTS marketing_events (
    event_id    BIGSERIAL    PRIMARY KEY,
    event_type  VARCHAR(40)  NOT NULL,
    source_type VARCHAR(20)  NOT NULL,
    source_id   VARCHAR(40)  NOT NULL,
    -- Meta's click id; when present it makes the report idempotent (the agent may retry).
    ctwa_clid   VARCHAR(500),
    customer_id INTEGER,
    occurred_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_marketing_events_ctwa_clid UNIQUE (ctwa_clid),
    CONSTRAINT ck_marketing_events_type CHECK (event_type IN ('conversation_started')),
    CONSTRAINT ck_marketing_events_source_type CHECK (source_type IN ('AD', 'IG_MEDIA'))
);
CREATE INDEX IF NOT EXISTS ix_marketing_events_occurred ON marketing_events (occurred_at);
