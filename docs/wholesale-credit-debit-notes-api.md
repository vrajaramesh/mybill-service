# Wholesale Credit Notes / Debit Notes & Configurable Document Semantics

Wholesale has three sales document types sharing one lifecycle (`DRAFT → ISSUED → PARTIALLY_PAID → PAID`,
any → `CANCELLED`, see `wholesale-sales-receipts-api.md`): **SALES_RECEIPT**, **CREDIT_NOTE**, **DEBIT_NOTE**.
Retail credit/debit notes (`debit_notes`, supplier purchase returns) are separate and untouched.

## Why semantics are configuration

Business terminology ("Credit Note", "Credit Invoice", …) may differ from the statutory GST document class, and
GST rules change. So *what a document type means* is stored in `wholesale_document_settings`, editable by an ADMIN
under **Settings → Document Types & Numbering**, not hard-coded:

| Setting | Values | Effect |
|---|---|---|
| `displayLabel` | text | name in the app |
| `printTitle` | text | title printed on the document |
| `gstClassification` | `TAX_INVOICE`, `BILL_OF_SUPPLY`, `CREDIT_NOTE`, `DEBIT_NOTE`, `RECEIPT_VOUCHER`, `OTHER` | statutory class, stored for GST reporting; drives the number label ("Invoice No." / "Note No." …) |
| `valueEffect` | `CHARGE` / `CREDIT` | CHARGE: customer owes more, payments can be recorded. CREDIT: customer owes less, no payments, credit limited to the referenced document |
| `stockEffect` | `OUT` / `IN` / `NONE` | applied on issue, reversed on cancel (`CREDIT` + `OUT` is rejected) |
| `referenceMode` | `NONE` / `OPTIONAL` / `REQUIRED` | reference to an earlier document |
| `allowedReferenceTypes` | any of `QUOTATION`, `SALES_RECEIPT`, `CREDIT_NOTE`, `DEBIT_NOTE` | which documents may be referenced (an external invoice is always allowed) |
| `reasonRequired` | boolean | a reason must be entered |

**History is never reinterpreted:** label, printed title, GST class, value effect and stock effect are **copied onto
each document** (refreshed while DRAFT, frozen on ISSUE). Printing, payments and cancellation (stock reversal) always
use the document's own copy.

### Defaults

| Type | Label / printed title | GST class | Value | Stock | Reference | Reason |
|---|---|---|---|---|---|---|
| `SALES_RECEIPT` | Sales Receipt / SALES RECEIPT | Tax invoice | CHARGE | OUT | optional: quotation | no |
| `CREDIT_NOTE` | Credit Note / TAX INVOICE (CREDIT SALE) | Tax invoice | CHARGE | OUT | optional: quotation, sales receipt | no |
| `DEBIT_NOTE` | Debit Note / DEBIT NOTE | GST debit note | CHARGE | NONE | **required**: sales receipt, credit note | **yes** |

(`CREDIT_NOTE` keeps the earlier business decision: a credit sale. To use it as a statutory GST credit note instead,
set class `CREDIT_NOTE`, value `CREDIT`, stock `IN` (returns) or `NONE` (allowances), reference `REQUIRED` — new notes
then reduce what the customer owes and are limited to the referenced invoice; old ones are unaffected.)

## References

A document can reference **one** of:

- an earlier wholesale document — `referenceDocumentId` (same customer, issued/partially paid/paid, not dated later);
- a quotation — `referenceQuotationId` (same customer, issued/accepted/converted);
- an external invoice — `externalReferenceNumber` + `externalReferenceDate` (pre-system or outside invoices).

The referenced number and date are copied (`reference.number`, `reference.date`) and printed
("Against Invoice SR-000004 dt. 05 Oct 2026"). Internal references are foreign keys
(`reference_document_id` → `wholesale_sales_documents`, `reference_quotation_id` → `wholesale_quotations`).
A referenced document that is cancelled blocks issuing until the reference is changed.

**Credit limits (value effect CREDIT, internal reference):** every product must be on the original; quantities
credited by all active credit documents may not exceed what was supplied; total credit may not exceed the original's
grand total. Shown as warnings on preview, enforced (`409`) on issue.

## Endpoints (in addition to the receipts API)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/wholesale/sales-documents/preview` | calculate without saving |
| POST | `/api/wholesale/sales-documents` | create DRAFT of any type → `201` |
| PUT | `/api/wholesale/sales-documents/{id}` | update DRAFT (type cannot change; converted documents are not editable) |
| POST | `/api/wholesale/sales-documents/{id}/issue` | `{ "payment": {…} }` optional, CHARGE documents only |
| POST | `/api/wholesale/sales-documents/{id}/payments` | CHARGE documents only |
| POST | `/api/wholesale/sales-documents/{id}/cancel` | `{ "reason": "…" }` — reverses the document's stock effect |
| DELETE | `/api/wholesale/sales-documents/{id}` | DRAFT only |
| GET | `/api/wholesale/sales-documents?type=DEBIT_NOTE&status=…` | list |
| GET | `/api/wholesale/document-settings` | all document types with labels + semantics |
| GET / PUT | `/api/wholesale/document-settings/{docType}` | numbering + semantics (PUT: ADMIN) |

**Editing:** only DRAFTs. Issued documents are never edited (tax documents); correct them with a credit or debit
note, or cancel and re-issue.

### Create a Debit Note

```json
POST /api/wholesale/sales-documents
{"docType": "DEBIT_NOTE", "customerId": 4, "documentDate": "2026-10-20",
 "referenceDocumentId": 15,
 "reason": "Price revision agreed on 18/10: +₹4/m on 50 m",
 "items": [{"wholesaleProductId": 12, "quantity": 50, "rate": 4.00, "gstPct": 5}]}
```
→ `201`, `status: "DRAFT"`, `reference: {"docType": "SALES_RECEIPT", "documentId": 15, "number": "SR-000004", "date": "2026-10-05"}`.
Errors: `400 "A Debit Note must reference the original document (or an external invoice number and date)"`,
`400 "Enter a reason for this Debit Note"`, `400 "Sales Receipt SR-000004 belongs to a different customer"`.

### Update semantics (ADMIN)

```json
PUT /api/wholesale/document-settings/CREDIT_NOTE
{"prefix": "CN", "separator": "-", "includeFinancialYear": false, "resetEachFinancialYear": false,
 "numberPadding": 6, "defaultValidityDays": 15, "defaultDueDays": 30,
 "displayLabel": "Credit Invoice", "printTitle": "TAX INVOICE (CREDIT SALE)",
 "gstClassification": "TAX_INVOICE", "valueEffect": "CHARGE", "stockEffect": "OUT",
 "referenceMode": "OPTIONAL", "allowedReferenceTypes": ["QUOTATION", "SALES_RECEIPT"], "reasonRequired": false}
```

### Document response additions

```json
{"docTypeLabel": "Debit Note", "printTitle": "DEBIT NOTE",
 "semantics": {"gstClassification": "DEBIT_NOTE", "valueEffect": "CHARGE", "stockEffect": "NONE"},
 "reference": {"docType": "SALES_RECEIPT", "documentId": 15, "quotationId": null, "number": "SR-000004", "date": "2026-10-05"},
 "reason": "Price revision agreed on 18/10",
 "auditTrail": [{"eventType": "CREATED", "eventAt": "…", "eventBy": "admin", "details": "Draft created"},
                {"eventType": "ISSUED", "eventAt": "…", "eventBy": "admin",
                 "details": "Issued as WDN-000001 (DEBIT NOTE; GST DEBIT_NOTE, CHARGE, stock NONE; ref SR-000004)"}]}
```

## Audit trail

`wholesale_document_events` records every action with user and time: `CREATED`, `CREATED_FROM_QUOTATION`, `UPDATED`,
`ISSUED` (with the semantics used), `PAYMENT_RECORDED`, `PAYMENT_VOIDED` (with reason), `CANCELLED` (with reason).
Existing documents received an `ISSUED` entry during migration. The who/when columns on the document
(`created_by/at`, `issued_by/at`, `converted_by/at`, `cancelled_by/at`) remain.

## Database (`V9__wholesale_credit_debit_notes.sql`)

- `wholesale_document_settings`: semantics columns; new `DEBIT_NOTE` row (default prefix `WDN-` so wholesale debit
  notes are not confused with retail `DN-…` supplier debit notes; changeable).
- `wholesale_sales_documents`: `DEBIT_NOTE` type; semantics snapshot columns (back-filled); `reference_document_id`,
  `reference_quotation_id`, `reference_doc_type`, `reference_number`, `reference_date`, `reference_external`,
  `reason`; credit documents can never record a paid amount.
- `wholesale_document_events`: audit trail.
