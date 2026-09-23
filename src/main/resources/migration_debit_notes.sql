-- Run this once on every existing firm schema in Neon.
-- New firms receive these tables automatically from FirmService.
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS gst_number VARCHAR(20);

CREATE TABLE IF NOT EXISTS debit_notes (
    debit_note_id SERIAL PRIMARY KEY,
    note_number VARCHAR(40) NOT NULL UNIQUE,
    purchase_id INTEGER NOT NULL REFERENCES purchases (purchase_id),
    note_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    reason TEXT,
    total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    gst NUMERIC(12,2) NOT NULL DEFAULT 0,
    final_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS debit_note_items (
    debit_note_item_id SERIAL PRIMARY KEY,
    debit_note_id INTEGER NOT NULL REFERENCES debit_notes (debit_note_id) ON DELETE CASCADE,
    purchase_item_id INTEGER NOT NULL REFERENCES purchase_items (purchase_item_id),
    product_id INTEGER NOT NULL REFERENCES products (product_id),
    quantity NUMERIC(10,2) NOT NULL,
    unit_price NUMERIC(10,2) NOT NULL,
    gst NUMERIC(10,2),
    total_amount NUMERIC(12,2) NOT NULL,
    final_amount NUMERIC(12,2) NOT NULL
);