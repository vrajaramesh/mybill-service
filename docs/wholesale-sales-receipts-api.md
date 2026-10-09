# Wholesale Sales Receipts API

Wholesale Sales Receipts can be created **directly** (this document) or by **converting a quotation**
(see `wholesale-quotation-conversion-api.md`). Credit Notes (credit sales) share the same lifecycle, payments and
endpoints under `/sales-documents`. Retail billing is not touched.
Auth / errors as in the other wholesale APIs: `{"message": "...", "fieldErrors": {...}}`.

## Lifecycle

```
DRAFT ──issue──▶ ISSUED ──payment──▶ PARTIALLY_PAID ──payment──▶ PAID
  │                │   └──────────── full payment ─────────────▶ │
  └──▶ CANCELLED ◀─┴────────────────────────┴────────────────────┘
```

| Status | Number | Stock | Editable | Payments |
|---|---|---|---|---|
| `DRAFT` | none (`Draft #id`) | not deducted | yes (direct receipts) | no |
| `ISSUED` | assigned | deducted | no | yes |
| `PARTIALLY_PAID` | assigned | deducted | no | yes |
| `PAID` | assigned | deducted | no | no (void a payment to reopen) |
| `CANCELLED` | kept if issued | restored if it had been issued | no | no |

`ISSUED / PARTIALLY_PAID / PAID` follow the recorded payments. Voiding a payment can move a document back
(e.g. `PAID → PARTIALLY_PAID`). Invalid transitions return `409`. Each response lists `allowedActions`
(`EDIT`, `DELETE`, `ISSUE`, `ADD_PAYMENT`, `VOID_PAYMENT`, `CANCEL`, `PRINT`, `PDF`, `SHARE`).

## Pricing rule

Lines are priced like quotation lines: `rate: null` → the product's quantity slab on the **receipt date**;
a number → kept as given (`rateSource` `RULE` if it equals the slab price, else `MANUAL`). The rate, HSN, unit,
GST % and amounts are **stored on the receipt**. Nothing re-prices a saved receipt: editing a draft sends the stored
rates back, and issuing recalculates GST/totals from the stored rates only. Current product prices never change a
historical sale.

## Endpoints

### Preview — `POST /api/wholesale/sales-receipts/preview`
### Create draft — `POST /api/wholesale/sales-receipts` → `201`
```json
{"customerId": 4, "receiptDate": "2026-10-05",
 "otherChargesLabel": "Packing", "otherChargesAmount": 100, "otherChargesGstPct": 18,
 "notes": null, "terms": null,
 "items": [{"wholesaleProductId": 12, "quantity": 50, "rate": null, "discountPct": 0, "gstPct": null, "description": null}]}
```
Receipt date cannot be in the future. `terms` empty → Sales Receipt default terms.

### Update draft — `PUT /api/wholesale/sales-receipts/{id}`
Same body. `409` unless the receipt is a `DRAFT` created directly (converted documents keep the quoted values).

### Delete draft — `DELETE /api/wholesale/sales-documents/{id}` → `204` (DRAFT only; otherwise cancel)

### Issue — `POST /api/wholesale/sales-documents/{id}/issue`
```json
{"payment": {"amount": 3697.00, "paymentMode": "UPI", "reference": "UTR 4521…", "paymentDate": "2026-10-05"}}
```
Body optional. Refreshes customer/profile snapshots, recalculates GST from stored rates, deducts stock
(all-or-nothing, `409` listing shortages), assigns the number (default `SR-000001`), then records the payment if given.

### Record payment — `POST /api/wholesale/sales-documents/{id}/payments`
```json
{"paymentDate": "2026-10-12", "amount": 1500.00, "paymentMode": "CASH", "reference": null, "notes": "2nd instalment"}
```
Only for `ISSUED` / `PARTIALLY_PAID`. `amount` ≤ balance due (`400` otherwise). Date between the document date and today.

### Void payment — `POST /api/wholesale/sales-documents/{id}/payments/{paymentId}/void`
```json
{"reason": "Cheque bounced"}
```
The payment stays in the history as `VOIDED`; paid amount and status are recalculated.

### Cancel — `POST /api/wholesale/sales-documents/{id}/cancel`
```json
{"reason": "Order cancelled by customer"}
```
Restores stock for issued documents. Payments stay in the history (a warning reminds you to refund).

### Search / filter — `GET /api/wholesale/sales-documents`
Query: `type` (`SALES_RECEIPT`, `CREDIT_NOTE`), `status` (comma-separated, e.g. `ISSUED,PARTIALLY_PAID`),
`q` (number or customer), `from`, `to`, `limit` (max 500). Rows include `amountPaid`, `balanceDue`,
`paymentStatus`, `paymentMode`, `dueDate`, `sourceQuotationNumber`.

### Get — `GET /api/wholesale/sales-documents/{id}`
```json
{"salesDocumentId": 15, "docType": "SALES_RECEIPT", "documentNumber": "SR-000002", "documentDate": "2026-10-05",
 "status": "PARTIALLY_PAID", "sourceQuotationId": null, "sourceQuotationNumber": null,
 "firm": {"…": "letterhead, bank, UPI"}, "customer": {"…": "billing + shipping + GSTIN"},
 "items": [{"itemName": "Madras Check Cotton", "hsnCode": "5208", "quantity": 50, "rate": 72.00, "gstPct": 5,
            "taxableAmount": 3600.00, "gstAmount": 180.00, "totalAmount": 3780.00, "…": "…"}],
 "totals": {"subtotal": 3600.00, "discountAmount": 0, "gstAmount": 198.00, "grandTotal": 3898.00, "…": "…"},
 "payment": {"paymentStatus": "PARTIAL", "paymentMode": "CASH", "amountPaid": 1500.00, "balanceDue": 2398.00, "dueDate": null},
 "payments": [{"paymentId": 3, "paymentDate": "2026-10-05", "amount": 1500.00, "paymentMode": "CASH", "status": "RECORDED",
               "createdBy": "admin", "…": "…"}],
 "createdBy": "admin", "issuedAt": "…", "issuedBy": "admin",
 "allowedActions": ["ADD_PAYMENT", "VOID_PAYMENT", "CANCEL", "PRINT", "PDF", "SHARE"], "warnings": []}
```

## Database (migration `V8__wholesale_sales_receipts.sql`)

- `wholesale_sales_documents.status` now `DRAFT | ISSUED | PARTIALLY_PAID | PAID | CANCELLED` (Phase 6 rows migrated:
  paid receipts → `PAID`); `document_number` nullable for drafts (required once issued); `issued_at`, `issued_by`;
  `amount_paid ≤ grand_total` enforced. `payment_status` (`PAID/PARTIAL/UNPAID`) is kept in step.
- New `wholesale_sales_payments` (date, amount > 0, method, reference, notes, `RECORDED`/`VOIDED`, audit, void reason).
  Phase 6 receipt payments were back-filled as the first payment.

## Tests

Unit tests (no database): `WholesaleSalesDocumentStatusTest` (lifecycle + payment status), plus the shared
`WholesaleQuotationCalculatorTest` used for receipt amounts.
