-- Wholesale module V5: wholesale business profile (letterhead, bank and UPI details for wholesale documents).
-- Exactly one row per firm schema (profile_id = 1). Independent of the retail store details, which live in
-- the browser (SettingsService/localStorage) and are not changed.
-- Columns are nullable so the Bank / UPI sections can be saved independently; the service enforces the
-- mandatory fields of each section and reports document readiness.

CREATE TABLE IF NOT EXISTS wholesale_business_profile (
    profile_id          SMALLINT     PRIMARY KEY DEFAULT 1,
    -- Business profile
    firm_name           VARCHAR(200),
    address             TEXT,
    city                VARCHAR(100),
    state_code          VARCHAR(2),
    state_name          VARCHAR(100),
    pin_code            VARCHAR(6),
    gst_number          VARCHAR(15),
    phone               VARCHAR(20),
    email               VARCHAR(150),
    website             VARCHAR(255),
    logo_url            VARCHAR(500),
    logo_public_id      VARCHAR(255),
    -- Bank details
    bank_name           VARCHAR(100),
    bank_account_name   VARCHAR(150),
    bank_account_number VARCHAR(18),
    bank_ifsc           VARCHAR(11),
    bank_branch         VARCHAR(150),
    -- UPI
    upi_id              VARCHAR(100),
    upi_qr_url          VARCHAR(500),
    upi_qr_public_id    VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by          VARCHAR(100),
    CONSTRAINT ck_wholesale_profile_singleton CHECK (profile_id = 1),
    CONSTRAINT ck_wholesale_profile_gst  CHECK (gst_number IS NULL OR gst_number ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
    CONSTRAINT ck_wholesale_profile_pin  CHECK (pin_code IS NULL OR pin_code ~ '^[1-9][0-9]{5}$'),
    CONSTRAINT ck_wholesale_profile_ifsc CHECK (bank_ifsc IS NULL OR bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    CONSTRAINT ck_wholesale_profile_acct CHECK (bank_account_number IS NULL OR bank_account_number ~ '^[0-9]{9,18}$')
);
