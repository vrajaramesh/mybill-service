-- Wholesale module V12: controlled Wholesale AI Assistant (tool-calling).
-- Adds the product knowledge the assistant may answer from, the business knowledge base (ordering, shipping, payment)
-- and per-conversation context. Retail tables are not touched.

-- ── Product knowledge (shown to customers only through the assistant's read-only tools) ─────────
ALTER TABLE wholesale_products ADD COLUMN IF NOT EXISTS fabric_type       VARCHAR(100);
ALTER TABLE wholesale_products ADD COLUMN IF NOT EXISTS available_colors  VARCHAR(1000);  -- comma separated
ALTER TABLE wholesale_products ADD COLUMN IF NOT EXISTS available_designs VARCHAR(1000);  -- comma separated
ALTER TABLE wholesale_products ADD COLUMN IF NOT EXISTS specifications    TEXT;           -- one "Name: Value" per line

-- ── Assistant settings + business knowledge base (single row) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS wholesale_assistant_settings (
    settings_id            SMALLINT      PRIMARY KEY DEFAULT 1,
    ordering_process       TEXT,
    shipping_info          TEXT,
    payment_terms          TEXT,
    business_hours         VARCHAR(500),
    additional_info        TEXT,
    unknown_reply          VARCHAR(500)  NOT NULL DEFAULT 'I don''t have that information available right now. Our team can confirm it for you.',
    share_stock_quantity   BOOLEAN       NOT NULL DEFAULT FALSE,  -- false: "available", never the exact quantity
    allow_quotation_drafts BOOLEAN       NOT NULL DEFAULT TRUE,   -- assistant may save DRAFT quotations
    auto_issue_quotations  BOOLEAN       NOT NULL DEFAULT FALSE,  -- false: staff approve (issue) every AI draft
    updated_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_by             VARCHAR(100),
    CONSTRAINT ck_wholesale_assistant_settings_singleton CHECK (settings_id = 1)
);
INSERT INTO wholesale_assistant_settings (settings_id, share_stock_quantity)
SELECT 1, COALESCE((SELECT share_stock_quantity FROM wholesale_instagram_settings WHERE settings_id = 1), FALSE)
ON CONFLICT (settings_id) DO NOTHING;

-- ── Conversation context (current product / quantity / last quotation) ──────────────────────────
ALTER TABLE wholesale_ig_conversations ADD COLUMN IF NOT EXISTS assistant_context JSONB;
UPDATE wholesale_ig_conversations
   SET assistant_context = jsonb_build_object('productId', last_product_id)
 WHERE last_product_id IS NOT NULL AND assistant_context IS NULL;
