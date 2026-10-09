-- Wholesale module V9: dedicated Credit Note / Debit Note documents with CONFIGURABLE semantics, references and
-- an audit trail. Retail credit/debit notes (debit_notes = purchase returns to suppliers) are not touched.
--
-- Business terminology may differ from statutory GST classification, so each wholesale document type is described by
-- configuration (wholesale_document_settings) instead of code:
--   display_label / print_title   what the app calls it / the printed title
--   gst_classification            TAX_INVOICE | BILL_OF_SUPPLY | CREDIT_NOTE | DEBIT_NOTE | RECEIPT_VOUCHER | OTHER
--   value_effect                  CHARGE (customer owes more) | CREDIT (customer owes less)
--   stock_effect                  OUT (goods leave) | IN (goods come back) | NONE
--   reference_mode                NONE | OPTIONAL | REQUIRED ; allowed_reference_types = comma list of doc types
--   reason_required               a reason must be entered
-- These values are COPIED onto every document when it is issued, so changing the configuration later only affects
-- new documents; issued documents keep printing, cancelling and reversing stock exactly as they were issued.

-- ── Document type configuration ──────────────────────────────────────────────────────────────────
ALTER TABLE wholesale_document_settings DROP CONSTRAINT IF EXISTS ck_wholesale_doc_settings_type;
ALTER TABLE wholesale_document_settings
    ADD CONSTRAINT ck_wholesale_doc_settings_type CHECK (doc_type IN ('QUOTATION', 'SALES_RECEIPT', 'CREDIT_NOTE', 'DEBIT_NOTE'));

ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS display_label           VARCHAR(60);
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS print_title             VARCHAR(80);
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS gst_classification      VARCHAR(20) NOT NULL DEFAULT 'TAX_INVOICE';
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS value_effect            VARCHAR(10) NOT NULL DEFAULT 'CHARGE';
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS stock_effect            VARCHAR(10) NOT NULL DEFAULT 'OUT';
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS reference_mode          VARCHAR(10) NOT NULL DEFAULT 'OPTIONAL';
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS allowed_reference_types VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE wholesale_document_settings ADD COLUMN IF NOT EXISTS reason_required         BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE wholesale_document_settings DROP CONSTRAINT IF EXISTS ck_wholesale_doc_settings_gstclass;
ALTER TABLE wholesale_document_settings ADD CONSTRAINT ck_wholesale_doc_settings_gstclass
    CHECK (gst_classification IN ('TAX_INVOICE', 'BILL_OF_SUPPLY', 'CREDIT_NOTE', 'DEBIT_NOTE', 'RECEIPT_VOUCHER', 'OTHER'));
ALTER TABLE wholesale_document_settings DROP CONSTRAINT IF EXISTS ck_wholesale_doc_settings_value;
ALTER TABLE wholesale_document_settings ADD CONSTRAINT ck_wholesale_doc_settings_value CHECK (value_effect IN ('CHARGE', 'CREDIT'));
ALTER TABLE wholesale_document_settings DROP CONSTRAINT IF EXISTS ck_wholesale_doc_settings_stock;
ALTER TABLE wholesale_document_settings ADD CONSTRAINT ck_wholesale_doc_settings_stock CHECK (stock_effect IN ('OUT', 'IN', 'NONE'));
ALTER TABLE wholesale_document_settings DROP CONSTRAINT IF EXISTS ck_wholesale_doc_settings_refmode;
ALTER TABLE wholesale_document_settings ADD CONSTRAINT ck_wholesale_doc_settings_refmode CHECK (reference_mode IN ('NONE', 'OPTIONAL', 'REQUIRED'));

-- Defaults keep the Phase 6 business decision: "Credit Note" = credit sale, printed as a tax invoice.
UPDATE wholesale_document_settings
   SET display_label = 'Sales Receipt', print_title = 'SALES RECEIPT', gst_classification = 'TAX_INVOICE',
       value_effect = 'CHARGE', stock_effect = 'OUT', reference_mode = 'OPTIONAL', allowed_reference_types = 'QUOTATION'
 WHERE doc_type = 'SALES_RECEIPT' AND display_label IS NULL;
UPDATE wholesale_document_settings
   SET display_label = 'Credit Note', print_title = 'TAX INVOICE (CREDIT SALE)', gst_classification = 'TAX_INVOICE',
       value_effect = 'CHARGE', stock_effect = 'OUT', reference_mode = 'OPTIONAL', allowed_reference_types = 'QUOTATION,SALES_RECEIPT'
 WHERE doc_type = 'CREDIT_NOTE' AND display_label IS NULL;
UPDATE wholesale_document_settings
   SET display_label = 'Quotation', print_title = 'QUOTATION', gst_classification = 'OTHER',
       value_effect = 'CHARGE', stock_effect = 'NONE', reference_mode = 'NONE', allowed_reference_types = ''
 WHERE doc_type = 'QUOTATION' AND display_label IS NULL;

-- Debit Note: GST debit note raising the value of an earlier supply (price revision, extra charges).
-- Must reference the original receipt / invoice and give a reason; moves no stock by default.
INSERT INTO wholesale_document_settings (doc_type, prefix, separator, include_financial_year, reset_each_financial_year,
        number_padding, default_due_days, default_terms, display_label, print_title, gst_classification, value_effect,
        stock_effect, reference_mode, allowed_reference_types, reason_required)
VALUES ('DEBIT_NOTE', 'WDN', '-', FALSE, FALSE, 6, 15, 'Issued against the original invoice referenced above.',
        'Debit Note', 'DEBIT NOTE', 'DEBIT_NOTE', 'CHARGE', 'NONE', 'REQUIRED', 'SALES_RECEIPT,CREDIT_NOTE', TRUE)
ON CONFLICT (doc_type) DO NOTHING;

-- ── Documents: per-document semantics snapshot, references, reason ───────────────────────────────
ALTER TABLE wholesale_sales_documents DROP CONSTRAINT IF EXISTS ck_wholesale_sales_docs_type;
ALTER TABLE wholesale_sales_documents
    ADD CONSTRAINT ck_wholesale_sales_docs_type CHECK (doc_type IN ('SALES_RECEIPT', 'CREDIT_NOTE', 'DEBIT_NOTE'));
-- Due dates are now decided by configuration (payable documents), not by document type.
ALTER TABLE wholesale_sales_documents DROP CONSTRAINT IF EXISTS ck_wholesale_sales_docs_credit;

ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS display_label      VARCHAR(60);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS print_title        VARCHAR(80);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS gst_classification VARCHAR(20);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS value_effect       VARCHAR(10);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS stock_effect       VARCHAR(10);

ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reference_document_id INTEGER
    REFERENCES wholesale_sales_documents (sales_document_id);
-- A quotation that is only referred to (not converted from); conversions keep using source_quotation_id.
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reference_quotation_id INTEGER
    REFERENCES wholesale_quotations (quotation_id);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reference_doc_type  VARCHAR(20);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reference_number    VARCHAR(60);
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reference_date      DATE;
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reference_external  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE wholesale_sales_documents ADD COLUMN IF NOT EXISTS reason              VARCHAR(500);

-- Back-fill existing documents with the semantics they were issued under.
UPDATE wholesale_sales_documents SET display_label = 'Sales Receipt', print_title = 'SALES RECEIPT',
       gst_classification = 'TAX_INVOICE', value_effect = 'CHARGE', stock_effect = 'OUT'
 WHERE doc_type = 'SALES_RECEIPT' AND display_label IS NULL;
UPDATE wholesale_sales_documents SET display_label = 'Credit Note', print_title = 'TAX INVOICE (CREDIT SALE)',
       gst_classification = 'TAX_INVOICE', value_effect = 'CHARGE', stock_effect = 'OUT'
 WHERE doc_type = 'CREDIT_NOTE' AND display_label IS NULL;
UPDATE wholesale_sales_documents d
   SET reference_doc_type = 'QUOTATION', reference_number = q.quotation_number, reference_date = q.quotation_date
  FROM wholesale_quotations q
 WHERE d.source_quotation_id = q.quotation_id AND d.reference_doc_type IS NULL;

ALTER TABLE wholesale_sales_documents ALTER COLUMN display_label SET NOT NULL;
ALTER TABLE wholesale_sales_documents ALTER COLUMN print_title SET NOT NULL;
ALTER TABLE wholesale_sales_documents ALTER COLUMN gst_classification SET NOT NULL;
ALTER TABLE wholesale_sales_documents ALTER COLUMN value_effect SET NOT NULL;
ALTER TABLE wholesale_sales_documents ALTER COLUMN stock_effect SET NOT NULL;
ALTER TABLE wholesale_sales_documents DROP CONSTRAINT IF EXISTS ck_wholesale_sales_docs_value;
ALTER TABLE wholesale_sales_documents ADD CONSTRAINT ck_wholesale_sales_docs_value CHECK (value_effect IN ('CHARGE', 'CREDIT'));
ALTER TABLE wholesale_sales_documents DROP CONSTRAINT IF EXISTS ck_wholesale_sales_docs_stock;
ALTER TABLE wholesale_sales_documents ADD CONSTRAINT ck_wholesale_sales_docs_stock CHECK (stock_effect IN ('OUT', 'IN', 'NONE'));
ALTER TABLE wholesale_sales_documents DROP CONSTRAINT IF EXISTS ck_wholesale_sales_docs_reftype;
ALTER TABLE wholesale_sales_documents ADD CONSTRAINT ck_wholesale_sales_docs_reftype
    CHECK (reference_doc_type IS NULL OR reference_doc_type IN ('QUOTATION', 'SALES_RECEIPT', 'CREDIT_NOTE', 'DEBIT_NOTE', 'EXTERNAL'));
-- A credit document can never be "paid" by the customer.
ALTER TABLE wholesale_sales_documents DROP CONSTRAINT IF EXISTS ck_wholesale_sales_docs_credit_unpaid;
ALTER TABLE wholesale_sales_documents ADD CONSTRAINT ck_wholesale_sales_docs_credit_unpaid
    CHECK (value_effect = 'CHARGE' OR amount_paid = 0);

CREATE INDEX IF NOT EXISTS idx_wholesale_sales_docs_reference ON wholesale_sales_documents (reference_document_id);

-- ── Audit trail ──────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS wholesale_document_events (
    event_id          INTEGER      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sales_document_id INTEGER      NOT NULL REFERENCES wholesale_sales_documents (sales_document_id) ON DELETE CASCADE,
    event_type        VARCHAR(30)  NOT NULL,
    event_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    event_by          VARCHAR(100),
    details           VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_wholesale_document_events_doc ON wholesale_document_events (sales_document_id, event_at);

-- History for documents that existed before the audit trail.
INSERT INTO wholesale_document_events (sales_document_id, event_type, event_at, event_by, details)
SELECT d.sales_document_id, 'ISSUED', COALESCE(d.issued_at, d.created_at), COALESCE(d.issued_by, d.created_by),
       'Recorded before the audit trail existed'
  FROM wholesale_sales_documents d
 WHERE d.status <> 'DRAFT'
   AND NOT EXISTS (SELECT 1 FROM wholesale_document_events e WHERE e.sales_document_id = d.sales_document_id);
