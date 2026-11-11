-- WhatsApp → Dropship intake, revised supplier mapping: the owner picks the supplier for every forwarded post from a
-- WhatsApp list of the suppliers active for dropship, most recently used first; a reminder after 2 h if not chosen.
-- (UPI / bank details stay in boutique_suppliers.payment_details.)

ALTER TABLE boutique_suppliers ADD COLUMN IF NOT EXISTS dropship_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE boutique_suppliers ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP;

ALTER TABLE wa_intake_batch ADD COLUMN IF NOT EXISTS supplier_asked_at TIMESTAMP;
ALTER TABLE wa_intake_batch ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMP;
