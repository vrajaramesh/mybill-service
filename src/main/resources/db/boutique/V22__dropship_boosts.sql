-- Boosted Instagram posts of dropship collections (Click-to-WhatsApp ads made by mybill when the owner taps
-- "Boost"). total = what was committed (daily_budget x days); spend = Meta's figure from the daily summary.
CREATE TABLE IF NOT EXISTS dropship_boosts (
    boost_id        SERIAL        PRIMARY KEY,
    collection_id   INTEGER       REFERENCES boutique_collections (collection_id) ON DELETE SET NULL,
    collection_code VARCHAR(30)   NOT NULL,
    owner_phone     VARCHAR(15),
    days            INTEGER       NOT NULL,
    daily_budget    NUMERIC(10,2) NOT NULL,
    total           NUMERIC(10,2) NOT NULL,
    audience        VARCHAR(20)   NOT NULL,              -- HYDERABAD, TS_AP, INDIA
    status          VARCHAR(20)   NOT NULL DEFAULT 'CREATING',
    campaign_id     VARCHAR(40),
    adset_id        VARCHAR(40),
    creative_id     VARCHAR(40),
    ad_id           VARCHAR(40),
    media_id        VARCHAR(40),
    error           TEXT,
    spend           NUMERIC(10,2),
    starts_at       TIMESTAMP,
    ends_at         TIMESTAMP,
    summary_sent_at TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_dropship_boosts_status CHECK (status IN ('CREATING', 'ACTIVE', 'STOPPED', 'ENDED', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS ix_dropship_boosts_created ON dropship_boosts (created_at);
