# Wholesale Quotations API

All endpoints need a firm login (`Authorization: Bearer <token>`); the firm comes from the token.
Errors use one shape: `{"message": "...", "fieldErrors": {"items[0].quantity": "..."}}` (`fieldErrors` only for validation).

| Status | Meaning |
|---|---|
| 400 | Validation / business rule failed (message says which) |
| 403 | Not allowed (settings changes need an ADMIN) |
| 404 | Quotation / customer / product not found |
| 409 | Wrong status for the action, business profile incomplete, or numbering conflict |

## Concepts

- **Pricing is quantity-based and never uses retail prices.** For each line the server finds the product's active
  quantity price rule covering the quantity on the *quotation date* (`/api/wholesale/products/{id}/price`).
  Send `rate: null` to use it, or send a rate to override (stored as `rateSource: "MANUAL"`, with the slab
  price kept in `ruleRate` for comparison).
- **Rates are frozen on the quotation.** Each line stores the rate, item name, HSN, unit and GST % used.
  Nothing re-prices a saved quotation; changing or deleting price rules later does not change it.
  (Duplicating with `reprice=true` creates a *new* draft priced with today's rules.)
- **Snapshots.** Customer details (billing + shipping) and the business profile (letterhead, bank, UPI) are copied
  onto the quotation while it is a draft and frozen when it is issued.
- **GST** is exclusive of rates. Place of supply = customer's shipping state (else billing state).
  Same state as the firm → CGST + SGST, otherwise IGST.
- **Amounts:** `gross = qty × rate`, `discount = gross × discount%`, `taxable = gross − discount`, GST on taxable.
  Other charges (e.g. freight) are taxable at their own GST %. Grand total is rounded to the nearest rupee
  (`roundOff` holds the difference).
- **Numbers** are assigned on **Issue** from the configured format (e.g. `QT/2026-27/0001`); drafts show `Draft #<id>`.
  Numbering is atomic per firm, and resets each financial year (April–March) unless configured otherwise.

### Status lifecycle

```
DRAFT ──issue──▶ ISSUED ──▶ ACCEPTED ──▶ CONVERTED   (set by the future credit-invoice phase)
  │                │  ├──▶ REJECTED
  │                │  └──▶ EXPIRED   (automatic once valid_until has passed)
  └──▶ CANCELLED ◀─┴── (ISSUED and ACCEPTED can also be cancelled)
```

Only **DRAFT** can be edited or deleted. To change anything else, **duplicate** it.
Each response has `allowedActions` (e.g. `["EDIT","DELETE","ISSUE","CANCEL","DUPLICATE","PRINT","PDF","SHARE"]`).

## Endpoints

### List — `GET /api/wholesale/quotations`
Query: `q` (number or customer name), `status` (comma-separated), `from`, `to` (ISO dates), `limit` (max 500).

```json
[{"quotationId":7,"quotationNumber":"QT/2026-27/0003","displayNumber":"QT/2026-27/0003","status":"ISSUED",
  "quotationDate":"2026-10-05","validUntil":"2026-10-20","wholesaleCustomerId":4,
  "customerName":"Ravi Kumar","customerBusinessName":"Ravi Textiles","grandTotal":3402.00}]
```

### Get — `GET /api/wholesale/quotations/{id}`
Returns the full quotation (see *Response* below).

### Preview — `POST /api/wholesale/quotations/preview`
Same body as Create; returns the calculated quotation **without saving** (used for live totals).

### Create — `POST /api/wholesale/quotations` → `201`
```json
{
  "customerId": 4,
  "quotationDate": "2026-10-05",
  "validUntil": null,
  "otherChargesLabel": "Freight",
  "otherChargesAmount": 250,
  "otherChargesGstPct": 18,
  "notes": "Delivery within 7 days",
  "terms": null,
  "items": [
    {"wholesaleProductId": 12, "quantity": 50, "rate": null, "discountPct": 10, "gstPct": null, "description": "Blue checks"}
  ]
}
```
- `validUntil` empty → quotation date + default validity days (numbering settings).
- `terms` empty → default terms from settings.
- `rate` empty → slab price; `gstPct` empty → slab GST, else product GST.
- Errors: `400 "Item 1: no price rule covers 9.5 Meters of 'Madras Check Cotton' on 2026-10-05. Add a price rule or enter a rate."`,
  `400 "Customer 'X' is inactive"`, `409 "Complete Settings → Wholesale Business Profile before generating documents. Missing: ..."`.

### Update — `PUT /api/wholesale/quotations/{id}` (DRAFT only)
Same body as Create. `409` if not a draft (`"Only draft quotations can be edited; this one is issued. Duplicate it to make changes."`).

### Delete — `DELETE /api/wholesale/quotations/{id}` → `204` (DRAFT only)

### Duplicate — `POST /api/wholesale/quotations/{id}/duplicate?reprice=true` → `201`
New DRAFT for the same customer dated today. `reprice=true` (default) uses today's price rules where one applies
(other lines keep their old rate); `reprice=false` keeps all original rates. `duplicatedFromId` links back.

### Issue — `POST /api/wholesale/quotations/{id}/issue`
DRAFT → ISSUED. Refreshes customer/profile snapshots, recalculates GST for the final place of supply (rates unchanged),
assigns the next number. `400` if the validity date has passed.

### Change status — `POST /api/wholesale/quotations/{id}/status`
```json
{"status": "CANCELLED", "reason": "Customer postponed the order"}
```
`status` ∈ `ACCEPTED`, `REJECTED`, `CANCELLED`, following the lifecycle (`409` otherwise).

### Response (Get / Create / Update / Issue / …)
```json
{
  "quotationId": 7, "quotationNumber": "QT/2026-27/0003", "displayNumber": "QT/2026-27/0003",
  "status": "ISSUED", "quotationDate": "2026-10-05", "validUntil": "2026-10-20",
  "firm": {"firmName": "Srisa Fabrics", "addressLines": ["12 Main Rd", "Hyderabad, Telangana - 500001"],
           "stateCode": "36", "stateName": "Telangana", "gstNumber": "36ABCDE1234F1Z5", "phone": "+919876543210",
           "email": null, "website": null, "logoUrl": "https://…/logo.png",
           "bank": {"bankName": "SBI", "accountName": "Srisa Fabrics", "accountNumber": "12345678901", "ifsc": "SBIN0001234", "branch": "Ameerpet"},
           "upi": {"upiId": "srisa@okhdfcbank", "payeeName": "Srisa Fabrics", "qrImageUrl": "https://…/qr.png"}},
  "customer": {"wholesaleCustomerId": 4, "customerName": "Ravi Kumar", "businessName": "Ravi Textiles",
               "gstNumber": "36AAAAA0000A1Z5", "phone": "+919000000000", "billingAddress": "…", "shippingAddress": "…", "…": "…"},
  "placeOfSupplyStateCode": "36", "interstate": false,
  "items": [{"lineNo": 1, "wholesaleProductId": 12, "itemName": "Madras Check Cotton", "hsnCode": "5208", "unit": "Meters",
             "quantity": 50, "rate": 72.00, "rateSource": "RULE", "ruleRate": 72.00, "priceRuleId": 3,
             "discountPct": 10, "grossAmount": 3600.00, "discountAmount": 360.00, "taxableAmount": 3240.00,
             "gstPct": 5, "cgstAmount": 81.00, "sgstAmount": 81.00, "igstAmount": 0, "gstAmount": 162.00, "totalAmount": 3402.00}],
  "totals": {"grossAmount": 3600.00, "discountAmount": 360.00, "subtotal": 3240.00,
             "otherChargesLabel": "Freight", "otherChargesAmount": 250.00, "otherChargesGstPct": 18, "otherChargesGstAmount": 45.00,
             "cgstAmount": 103.50, "sgstAmount": 103.50, "igstAmount": 0, "gstAmount": 207.00, "roundOff": 0, "grandTotal": 3697.00},
  "allowedActions": ["ACCEPT", "REJECT", "CANCEL", "DUPLICATE", "PRINT", "PDF", "SHARE"],
  "warnings": []
}
```
`warnings` (on save/preview) flag e.g. stock shortfalls (quotations never reserve stock) or a customer without a state.

## Numbering settings

### `GET /api/wholesale/document-settings/QUOTATION`
```json
{"docType":"QUOTATION","prefix":"QT","separator":"/","includeFinancialYear":true,"resetEachFinancialYear":true,
 "numberPadding":4,"defaultValidityDays":15,"defaultTerms":"…","currentPeriod":"2026-27","lastNumber":3,
 "nextNumberPreview":"QT/2026-27/0004","updatedAt":"…","updatedBy":"admin"}
```

### `PUT /api/wholesale/document-settings/QUOTATION` (ADMIN)
```json
{"prefix":"SQ","separator":"-","includeFinancialYear":false,"resetEachFinancialYear":false,
 "numberPadding":5,"defaultValidityDays":30,"defaultTerms":"…","nextNumber":101}
```
`nextNumber` (optional) continues numbering from a given value in the current period (e.g. from a paper book);
it must be greater than the last number used. Numbers that already exist are skipped automatically.

## Database

| Table | Notes |
|---|---|
| `wholesale_document_settings` | One row per document type (`QUOTATION`). |
| `wholesale_document_counters` | `(doc_type, period)` → last number; incremented atomically on issue. |
| `wholesale_quotations` | FK → `wholesale_customers` (no cascade: customers with quotations cannot be deleted). Customer + firm snapshots. `quotation_number` unique, set on issue. Self-FK `duplicated_from_id`. |
| `wholesale_quotation_items` | FK → quotation (cascade), → `wholesale_products` (no cascade: products on quotations cannot be deleted), → `wholesale_product_price_rules` (`ON DELETE SET NULL`, reference only). |

Created by migration `db/wholesale/V6__wholesale_quotations.sql`.

## Tests

Unit tests (no database needed): `WholesaleQuotationCalculatorTest`, `WholesaleDocumentNumberingTest`,
`WholesaleQuotationStatusTest`.
Run: `mvn test -Dtest='WholesaleQuotation*Test,WholesaleDocumentNumberingTest'`.
