# Wholesale Inventory API

Wholesale stock (`wholesale_products.available_quantity`) is completely separate from retail stock
(`products.stock_quantity`), which is never read or changed here.

## How stock changes

All wholesale stock changes go through `WholesaleInventoryService` — nothing else updates the quantity:

1. one atomic `UPDATE wholesale_products … RETURNING available_quantity` (row lock → concurrent movements of a product
   are serialised and the returned balance is exact);
2. if **negative stock is not allowed** (default), the update only succeeds when the result stays ≥ 0;
3. a row is appended to `wholesale_inventory_ledger` with the **source document** and the resulting balance.

It runs inside the business transaction, so a document and its stock movements succeed or fail together. A
document with several lines is all-or-nothing: if any line is short, nothing changes and a `409` lists every shortage.

| Business event | Ledger rows (source document) |
|---|---|
| Wholesale purchase created | `PURCHASE` in (purchase) |
| Wholesale purchase cancelled | `REVERSAL` out for each PURCHASE row (purchase) — purchases are now **cancelled, never deleted** |
| Sales document issued, stock effect OUT | `SALE` (Sales Receipt) / `CREDIT` (Credit Note) / `DEBIT` (Debit Note) out |
| Sales document issued, stock effect IN | `RETURN` in |
| Sales document issued, stock effect NONE | none |
| Sales document cancelled | `REVERSAL` of each of its rows |
| Stock adjustment | `ADJUSTMENT` in (+) / out (−) (stock adjustment) |
| Ledger start (migration) | `OPENING` = stock on hand at that moment (stock adjustment `OPENING`) |

The stock effect of a sales document is the one **frozen on the document** when it was issued (configurable per
document type, see `wholesale-credit-debit-notes-api.md`). Reversals always undo exactly the rows that were posted.
Documents issued before the ledger existed are reversed from their recorded stock effect instead.

Ledger rows are append-only. A row can be reversed only once (unique index on `reverses_ledger_id`).

## Endpoints (`/api/wholesale/inventory`)

| Method | Path | Notes |
|---|---|---|
| GET | `/stock?q=&includeInactive=&onlyInStock=` | current stock per product, stock value at last purchase rate, last movement date, `inSync` (ledger Σin−Σout = quantity) |
| GET | `/ledger?productId=&from=&to=&type=SALE,PURCHASE&referenceType=PURCHASE&q=<doc no>&limit=` | newest first, max 1000 |
| GET | `/products/{id}/history?from=&to=` | opening, in, out, closing + entries (oldest first) |
| GET | `/movements?from=&to=&q=&onlyMoved=true` | per product: opening + in − out = closing |
| GET | `/adjustments`, `/adjustments/{id}` | stock adjustment documents |
| POST | `/adjustments` | **ADMIN**; creates a numbered adjustment (`ADJ-000001`) and its ledger rows |
| GET / PUT | `/settings` | `{"allowNegativeStock": false}`; PUT is **ADMIN** |

Purchases: `POST /api/wholesale/purchases/{id}/cancel` `{"reason": "…"}` (the old `DELETE` now cancels with reason
"Deleted"). A cancelled supplier invoice number can be entered again.

Opening / closing in history and movements are computed **by transaction date** (the document date).
`balanceQuantity` on each ledger row is the balance **at the time it was posted**.

### Examples

```json
POST /api/wholesale/inventory/adjustments
{"adjustmentDate": "2026-10-05", "reason": "Stock count 05/10",
 "items": [{"wholesaleProductId": 12, "quantityChange": -3, "notes": "water damage"},
           {"wholesaleProductId": 15, "quantityChange": 2.5}]}
```
→ `201 {"adjustmentNumber": "ADJ-000001", …}`;
`409 "Not enough wholesale stock: Madras Check Cotton (needs 3, available 1). Negative wholesale stock is not allowed …"`.

```json
GET /api/wholesale/inventory/ledger?productId=12&from=2026-10-01&to=2026-10-31
[{"ledgerId": 41, "transactionDate": "2026-10-05", "productName": "Madras Check Cotton", "unit": "Meters",
  "transactionType": "SALE", "referenceType": "SALES_RECEIPT", "referenceId": 15, "referenceNumber": "SR-000004",
  "quantityIn": 0, "quantityOut": 50, "balanceQuantity": 370, "rate": 72.00, "createdBy": "admin",
  "notes": "Sales Receipt to Ravi Kumar"}]
```

```json
GET /api/wholesale/inventory/products/12/history?from=2026-10-01&to=2026-10-31
{"productName": "Madras Check Cotton", "unit": "Meters", "availableQuantity": 370,
 "openingQuantity": 420, "totalIn": 0, "totalOut": 50, "closingQuantity": 370, "entries": [ … ]}
```

## Database (`V10__wholesale_inventory_ledger.sql`)

| Table | Purpose |
|---|---|
| `wholesale_inventory_ledger` | `ledger_id`, `wholesale_product_id`, `transaction_type`, `reference_type`, `reference_id`, `reference_number`, `reverses_ledger_id`, `quantity_in`, `quantity_out` (exactly one > 0), `balance_quantity`, `unit`, `rate`, `transaction_date`, `notes`, `created_at`, `created_by` |
| `wholesale_stock_adjustments` (+ `_items`) | adjustment documents (reason required; `is_opening` for the ledger-start balance) |
| `wholesale_inventory_settings` | `allow_negative_stock` (default false) |
| `wholesale_purchases` | new `status` (ACTIVE / CANCELLED), `cancelled_*`, `created_by`; invoice uniqueness only among ACTIVE purchases |

The old hard rule `available_quantity >= 0` on `wholesale_products` was replaced by the configurable setting.
Products with ledger history cannot be deleted (deactivate instead).
