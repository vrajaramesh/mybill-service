# Wholesale Quotation Conversion API

Converts an issued/accepted wholesale quotation into a **Sales Receipt** (paid at sale) or a **Credit Note**
(a *credit sale*: customer pays later; printed as **"TAX INVOICE (CREDIT SALE)"**). Wholesale only — the retail
bills / invoice workflow is not touched. Auth and error format are the same as the quotations API
(`{"message": "...", "fieldErrors": {...}}`).

```
Quotation QT/2026-27/0003  (ISSUED or ACCEPTED)
      |
      +--> Sales Receipt  SR-000001   (paid; stock deducted)
      |
      +--> Credit Note    CN-000001   (due date, UNPAID; stock deducted)
Quotation status → CONVERTED, document.sourceQuotationId = quotation id
```

## What is copied (never re-priced)

Customer name/business, phone, email, GSTIN, billing address, shipping address, place of supply,
CGST/SGST vs IGST, every item (name, code, **HSN**, unit, description, **quantity**, **rate captured on the
quotation**, discount %, **GST %**, taxable, GST and line totals) and all totals (subtotal, discount, other charges,
GST, round-off, grand total). Current price rules are **not** consulted. Each document line keeps
`sourceQuotationItemId`; the document keeps `sourceQuotationId`.

The letterhead, bank and UPI details come from the current Wholesale Business Profile (so payment details are
up to date). If the firm's **state** has changed since the quotation was issued the conversion is refused (409),
because the copied GST split would no longer be valid.

## Rules

| Rule | Result |
|---|---|
| Quotation must be `ISSUED` or `ACCEPTED` | otherwise `409` (draft → issue first; expired/rejected/cancelled → duplicate it) |
| Expired quotations | expiry is applied first, so an overdue `ISSUED` quotation cannot be converted |
| Already `CONVERTED` with an active (issued) document | `409` unless `allowDuplicate: true` |
| Stock | every line's quantity is deducted from wholesale stock; if any line is short → `409` listing shortages, nothing changes |
| Document date | defaults to today; not in the future; not before the quotation date |
| Sales Receipt | `paymentMode` required (`CASH`, `UPI`, `BANK_TRANSFER`, `CARD`, `CHEQUE`); `amountPaid` = grand total, `paymentStatus` = `PAID` |
| Credit Note | `dueDate` defaults to document date + configured due days; `paymentStatus` = `UNPAID` |
| Concurrency | the quotation row is locked for the whole conversion; a double-click cannot create two documents |
| Atomicity | document, items, stock, number and quotation status are saved in one transaction (all or nothing) |

### Status transitions

Quotation: `ISSUED → CONVERTED`, `ACCEPTED → CONVERTED` (conversion); `CONVERTED → ACCEPTED` only automatically
when **every** document created from it has been cancelled. `CONVERTED` quotations cannot be cancelled, edited or
deleted.
Sales document: `ISSUED → CANCELLED` (final). Cancelling restores the stock.

## Endpoints

### Convert — `POST /api/wholesale/quotations/{id}/convert` → `201`
```json
{"convertTo": "SALES_RECEIPT", "documentDate": "2026-10-05", "paymentMode": "UPI",
 "paymentReference": "UTR 4521...", "notes": null, "allowDuplicate": false}
```
```json
{"convertTo": "CREDIT_NOTE", "dueDate": "2026-11-04"}
```
Response: the created document (see *Document*). Errors, e.g.
`409 "Quotation QT/2026-27/0003 is already converted (SR-000001). Confirm \"convert again\" if you really want another document."`,
`409 "Not enough wholesale stock: Madras Check Cotton (needs 50 Meters, available 20). Record a wholesale purchase first."`,
`400 "Choose how the customer paid (cash, UPI, bank transfer, card or cheque)"`.

### Quotation conversion status
`GET /api/wholesale/quotations/{id}` now also returns:
```json
{"conversionStatus": "CONVERTED",
 "conversions": [{"salesDocumentId": 9, "docType": "SALES_RECEIPT", "docTypeLabel": "Sales Receipt",
                  "documentNumber": "SR-000001", "documentDate": "2026-10-05", "status": "ISSUED",
                  "grandTotal": 3697.00, "convertedAt": "2026-10-05T16:20:11", "convertedBy": "admin"}]}
```
`conversionStatus`: `NOT_CONVERTED`, `CONVERTED` (has an issued document) or `CONVERSION_CANCELLED`.
`allowedActions` includes `CONVERT` for ISSUED / ACCEPTED / CONVERTED quotations.

### List documents — `GET /api/wholesale/sales-documents`
Query: `type` (`SALES_RECEIPT`, `CREDIT_NOTE`, comma-separated), `status` (`ISSUED`, `CANCELLED`), `q` (number or
customer), `from`, `to`, `limit` (max 500). Rows include `sourceQuotationId` / `sourceQuotationNumber`,
`paymentStatus`, `dueDate`.

### Get document — `GET /api/wholesale/sales-documents/{id}`
### Cancel document — `POST /api/wholesale/sales-documents/{id}/cancel`
```json
{"reason": "Customer returned the order"}
```
Restores stock; if no other issued document exists for the source quotation it goes back to `ACCEPTED`.

### Document
```json
{"salesDocumentId": 9, "docType": "SALES_RECEIPT", "docTypeLabel": "Sales Receipt", "printTitle": "SALES RECEIPT",
 "documentNumber": "SR-000001", "documentDate": "2026-10-05", "status": "ISSUED",
 "sourceQuotationId": 7, "sourceQuotationNumber": "QT/2026-27/0003",
 "firm": {"…": "business profile at conversion"}, "customer": {"…": "copied from the quotation"},
 "placeOfSupplyStateCode": "36", "interstate": false,
 "items": [{"lineNo": 1, "sourceQuotationItemId": 21, "itemName": "Madras Check Cotton", "hsnCode": "5208",
            "quantity": 50, "rate": 72.00, "gstPct": 5, "taxableAmount": 3240.00, "gstAmount": 162.00, "totalAmount": 3402.00, "…": "…"}],
 "totals": {"subtotal": 3240.00, "gstAmount": 207.00, "grandTotal": 3697.00, "…": "…"},
 "payment": {"paymentMode": "UPI", "paymentReference": "UTR 4521...", "amountPaid": 3697.00,
             "paymentStatus": "PAID", "dueDate": null, "balanceDue": 0.00},
 "createdAt": "…", "createdBy": "admin", "convertedAt": "…", "convertedBy": "admin",
 "cancelledAt": null, "cancelledBy": null, "cancelReason": null,
 "allowedActions": ["PRINT", "PDF", "SHARE", "CANCEL"]}
```

### Numbering
`GET|PUT /api/wholesale/document-settings/SALES_RECEIPT` and `/CREDIT_NOTE` (same shape as `QUOTATION`, plus
`defaultDueDays` used by Credit Notes). Defaults: `SR-000001`, `CN-000001` (continuous, 6 digits).

## Database (migration `V7__wholesale_quotation_conversion.sql`)

| Table | Notes |
|---|---|
| `wholesale_sales_documents` | `doc_type`, unique `document_number`, `source_quotation_id` → `wholesale_quotations`, `wholesale_customer_id` → `wholesale_customers` (customers with documents cannot be deleted), copied customer/address/GST snapshot, copied totals, payment fields, audit (`created_by/at`, `converted_by/at`, `cancelled_by/at`, `cancel_reason`). |
| `wholesale_sales_document_items` | copied lines; `source_quotation_item_id` → `wholesale_quotation_items`; `wholesale_product_id` → `wholesale_products` (products on documents cannot be deleted). |
| `wholesale_document_settings` | new rows `SALES_RECEIPT`, `CREDIT_NOTE`; new column `default_due_days`. |
