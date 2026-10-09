-- Wholesale module V13: repair. The purchase-cancellation part of V10 was added to V10 after V10 had already been
-- applied to existing databases, so those databases never received it. Re-applies it idempotently (no-op where V10
-- already contained it). Wholesale tables only.
ALTER TABLE wholesale_purchases ADD COLUMN IF NOT EXISTS status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE wholesale_purchases ADD COLUMN IF NOT EXISTS cancelled_at  TIMESTAMP;
ALTER TABLE wholesale_purchases ADD COLUMN IF NOT EXISTS cancelled_by  VARCHAR(100);
ALTER TABLE wholesale_purchases ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(255);
ALTER TABLE wholesale_purchases ADD COLUMN IF NOT EXISTS created_by    VARCHAR(100);
ALTER TABLE wholesale_purchases DROP CONSTRAINT IF EXISTS ck_wholesale_purchases_cancel_status;
ALTER TABLE wholesale_purchases ADD CONSTRAINT ck_wholesale_purchases_cancel_status CHECK (status IN ('ACTIVE', 'CANCELLED'));
-- A cancelled supplier invoice may be entered again: uniqueness only among ACTIVE purchases.
DROP INDEX IF EXISTS uq_wholesale_purchases_supplier_invoice;
CREATE UNIQUE INDEX IF NOT EXISTS uq_wholesale_purchases_supplier_invoice
    ON wholesale_purchases (supplier_id, LOWER(invoice_number)) WHERE status = 'ACTIVE';
