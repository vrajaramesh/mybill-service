# Boutique Agent API (`/api/agent/**`)

This is an internal API for the **srisa-agent** service (the AI sales agent on WhatsApp and Instagram). It serves the boutique catalog and handles customers, measurements, price quotes and draft orders for **one firm**, the one set by `AGENT_TENANT_SCHEMA` (default `firm_srisa`). The admin UI never uses it.

- JSON with **snake_case** field names. Money is INR as JSON numbers with 2 decimals, and every response that includes money also has `"currency": "INR"`.
- **All prices are calculated by the server.** The agent should show the `total` from `/orders/quote` or `/orders`, and never add amounts up itself.

## Authentication

Every request needs a **Google-signed ID token** for the agent's service account:

```
Authorization: Bearer <Google ID token>
```

mybill-service checks:
- the signature, using Google's public keys
- the issuer is `accounts.google.com` or `https://accounts.google.com`
- the token hasn't expired
- the audience is `AGENT_API_AUDIENCE`
- the email is `AGENT_ALLOWED_SA_EMAIL`
- `email_verified` is true

| Situation | Status | `code` |
|---|---|---|
| No header, malformed, expired, wrong audience or not issued by Google | 401 | `UNAUTHORIZED` |
| Valid Google token, but for a different account | 403 | `FORBIDDEN` |
| `AGENT_API_AUDIENCE` / `AGENT_ALLOWED_SA_EMAIL` not configured | 503 | `AGENT_API_NOT_CONFIGURED` |
| The agent's firm schema doesn't exist | 503 | `TENANT_UNAVAILABLE` |

The app's own login JWT is **not** accepted on these paths, and Google tokens aren't accepted anywhere else. The existing UI authentication is unchanged.

**Configuration** (`application.properties`, set as env vars in `mybill-service-deploy.yml`):

| Property | Env var | Production value |
|---|---|---|
| `agent.api.audience` | `AGENT_API_AUDIENCE` | `https://mybill-service-276371417285.us-central1.run.app` |
| `agent.allowed-sa-email` | `AGENT_ALLOWED_SA_EMAIL` | `srisa-agent-sa@project-0708fb26-e204-4400-ae9.iam.gserviceaccount.com` |
| `agent.tenant-schema` | `AGENT_TENANT_SCHEMA` | `firm_srisa` |
| `agent.auth.enabled` | — | `true`. Only `application-local.properties` sets `false`; with any other profile the app refuses to start. |
| `boutique.delivery.flat-charge` | `BOUTIQUE_DELIVERY_FLAT_CHARGE` | `100` |
| `boutique.delivery.free-above` | `BOUTIQUE_DELIVERY_FREE_ABOVE` | `2500` |
| `boutique.delivery.transit-days` | `BOUTIQUE_DELIVERY_TRANSIT_DAYS` | `5` (courier days added after stitching) |

The audience must match **exactly** the string srisa-agent asks for when it mints its token. Use the URL above, not the `…-6rjdx4lmcq-uc.a.run.app` alias.

### Calling the API from your Mac

1. Allow your user to mint tokens for the agent's service account (one time):

   ```bash
   gcloud iam service-accounts add-iam-policy-binding \
     srisa-agent-sa@project-0708fb26-e204-4400-ae9.iam.gserviceaccount.com \
     --member="user:srisafabricshyd@gmail.com" \
     --role="roles/iam.serviceAccountTokenCreator"
   ```

2. Get a token (valid for 1 hour) and call the API:

   ```bash
   BASE=https://mybill-service-276371417285.us-central1.run.app
   TOKEN=$(gcloud auth print-identity-token \
     --impersonate-service-account=srisa-agent-sa@project-0708fb26-e204-4400-ae9.iam.gserviceaccount.com \
     --audiences=$BASE --include-email)
   curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/agent/size-chart
   ```

   `--include-email` is needed: without it, impersonated tokens have no `email` claim and are rejected with 403.

When srisa-agent runs on Cloud Run as `srisa-agent-sa`, it gets the same token from the metadata server, for example with `google.oauth2.id_token.fetch_id_token(request, audience)` in Python. mybill-service is public (`--allow-unauthenticated`), so it doesn't need `roles/run.invoker`; the app checks the token itself.

**Local development:** run mybill-service with the `local` profile. Agent authentication is off there, so `curl http://localhost:8080/api/agent/size-chart` works without a token, and requests still go to `firm_srisa`.

## Errors

Every error has the same shape:

```json
{ "code": "OPTION_GROUP_CONFLICT", "message": "Only one SLEEVE option can be selected",
  "details": { "option_group": "SLEEVE", "option_ids": [3, 4] } }
```

| Status | `code` values |
|---|---|
| 400 | `VALIDATION_FAILED` (`details.fields` maps each field to its message), `MALFORMED_REQUEST` |
| 404 | `DESIGN_NOT_FOUND`, `CUSTOMER_NOT_FOUND`, `MEASUREMENTS_NOT_FOUND`, `ORDER_NOT_FOUND` |
| 409 | `PHONE_IN_USE`, `INSTAGRAM_IN_USE`, `IDEMPOTENCY_KEY_REUSED`, `CONFLICT` |
| 422 | `PINCODE_NOT_SERVICEABLE`, `DESIGN_INACTIVE`, `DESIGN_NOT_PRICED`, `INVALID_SIZE` (`details.valid_sizes`), `INVALID_OPTION`, `DUPLICATE_OPTION`, `OPTION_GROUP_CONFLICT`, `CUSTOMER_NOT_FOUND` (in an order body) |

## Endpoints

### `GET /api/agent/designs/{code}`

Returns one active design, with its images (in display order) and customisation options grouped by type. The code isn't case-sensitive. Returns 404 if the design doesn't exist or isn't active. The options include the design's own options plus the global ones (`applies_to_all_designs: true`).

```json
{
  "code": "KUR-101", "name": "Anarkali Kurta - Rose", "garment_type": "Kurta",
  "description": "Floor-length anarkali with flared panels.", "fabric_description": "Rayon crepe, rose pink",
  "base_price": 1899.00, "lead_time_days": 7, "currency": "INR",
  "images": [ { "url": "https://…/kur-101-front.jpg", "sort_order": 0 } ],
  "option_groups": [
    { "group": "NECK", "options": [
        { "option_id": 9,  "name": "Round neck",      "price_delta": 0.00,   "applies_to_all_designs": false },
        { "option_id": 10, "name": "Sweetheart neck", "price_delta": 200.00, "applies_to_all_designs": false } ] },
    { "group": "SLEEVE", "options": [
        { "option_id": 4, "name": "Sleeveless",  "price_delta": 0.00,   "applies_to_all_designs": true },
        { "option_id": 6, "name": "Full sleeve", "price_delta": 150.00, "applies_to_all_designs": true } ] }
  ]
}
```

### `GET /api/agent/designs/latest?limit=4`

Returns the newest active designs that have a code. `limit` can be 1 to 20 (default 4).

```json
[ { "code": "KUR-103", "name": "A-line Kurta - Mustard", "base_price": 2499.00, "currency": "INR",
    "image_url": "https://…/kur-103-front.jpg" } ]
```

### `GET /api/agent/size-chart`

Returns body measurements in inches, smallest size first.

```json
[ { "size_code": "XS", "bust_in": 32.0, "waist_in": 26.0, "hip_in": 35.0, "shoulder_in": 13.5, "length_in": 44.0 },
  { "size_code": "M",  "bust_in": 36.0, "waist_in": 30.0, "hip_in": 39.0, "shoulder_in": 14.5, "length_in": 45.0 } ]
```

### `POST /api/agent/customers/upsert`

```json
{ "phone_e164": "+919876543210", "instagram_user_id": "17841400000000000", "name": "Asha Rao" }
```

At least one of `phone_e164` and `instagram_user_id` is required. The phone must be in E.164 format.

How the customer is found:
1. By `phone_e164`.
2. Otherwise, by `instagram_user_id`.
3. Otherwise, for +91 numbers, an existing shop customer whose old free-text phone is the same 10 digits and who isn't linked yet (the newest one, if several match).
4. Otherwise, a new customer is created (`source` is `WHATSAPP`, or `INSTAGRAM` if there's no phone).

Fields that are empty on the customer are filled in. **Existing values are never overwritten.** The response is **201** if a customer was created and **200** if one was found. If the phone or Instagram id already belongs to a *different* customer, the response is 409.

```json
{ "customer_id": 512, "name": "Asha Rao", "phone_e164": "+919876543210", "instagram_user_id": "17841400000000000",
  "source": "WHATSAPP", "created": true, "created_at": "2026-10-17T18:02:11", "updated_at": "2026-10-17T18:02:11" }
```

### `GET /api/agent/customers/{id}/measurements`

Returns the customer's **latest** size profile. Returns 404 `MEASUREMENTS_NOT_FOUND` if none has been recorded, or `CUSTOMER_NOT_FOUND` if the customer doesn't exist.

```json
{ "measurement_id": 77, "customer_id": 512, "size_code": "M", "height_cm": 160.0, "bust_in": 36.0,
  "waist_in": 30.0, "hip_in": 39.0, "fit_preference": "REGULAR", "notes": null, "source": "AGENT",
  "created_at": "2026-10-17T18:05:40" }
```

### `POST /api/agent/customers/{id}/measurements`

```json
{ "size_code": "M", "height_cm": 160, "bust_in": 36, "waist_in": 30, "hip_in": 39, "fit_preference": "REGULAR", "notes": "Prefers loose sleeves" }
```

- `size_code` is required and must be in the size chart; otherwise 422 `INVALID_SIZE`.
- Allowed ranges: height 120–200 cm, bust 24–60 in, waist 20–56 in, hip 26–64 in. A value outside them gets a 400; this catches centimetres entered as inches.
- `fit_preference` is `SNUG`, `REGULAR` or `RELAXED`.

Every save **adds a new row**, so the history is kept. The response is **201** with the saved profile, in the same shape as the GET. The rows are stored in the shop's `customer_measurements` table (profile "Online"), so staff can see them in the Boutique screen.

### `POST /api/agent/orders/quote`

Returns a price. Nothing is saved.

```json
{ "design_code": "KUR-101", "size_code": "M", "option_ids": [6, 10], "qty": 1, "pincode": "500001" }
```

```json
{ "design_code": "KUR-101", "design_name": "Anarkali Kurta - Rose", "size_code": "M", "lead_time_days": 7,
  "breakdown": {
    "base_price": 1899.00,
    "options": [ { "option_id": 6,  "option_group": "SLEEVE", "name": "Full sleeve",     "price_delta": 150.00 },
                 { "option_id": 10, "option_group": "NECK",   "name": "Sweetheart neck", "price_delta": 200.00 } ],
    "options_total": 350.00, "unit_price": 2249.00, "qty": 1, "subtotal": 2249.00,
    "delivery_charge": 100.00, "free_delivery_above": 2500.00, "total": 2349.00, "currency": "INR" },
  "serviceable": true, "transit_days": 5, "estimated_delivery_date": "2026-10-29" }
```

`estimated_delivery_date` = today (Asia/Kolkata) + the design's `lead_time_days` + `transit_days`, counted in calendar days. It is `null` when `serviceable` is false.

**Serviceability:** for now this only checks the pincode format (6 digits, not starting with 0). A malformed `pincode` in a quote is rejected with 400. A Shiprocket check will plug in later behind the `ShippingService` interface.

How the price is worked out:
- `unit_price` = `base_price` + the sum of the options' `price_delta`
- `subtotal` = `unit_price` × `qty`
- `delivery_charge` is the flat charge, or 0 when `subtotal` ≥ `free_delivery_above`
- `total` = `subtotal` + `delivery_charge`

`pincode` is optional and only checked for format for now. The response is 404 if the design doesn't exist, and 422 for:
- an inactive design
- an unknown size
- an option that belongs to another design or is inactive
- two options from one group, or the same option twice

### `POST /api/agent/orders`

Creates a **DRAFT** order, priced by the server in the same way as the quote.

```json
{ "customer_id": 512, "design_code": "KUR-101", "size_code": "M", "option_ids": [6, 10], "qty": 1,
  "delivery": { "name": "Asha Rao", "phone": "+91 98765 43210", "address_line1": "12 MG Road", "address_line2": "Flat 3B",
                "city": "Hyderabad", "state": "Telangana", "pincode": "500001" },
  "measurements": { "size_code": "M", "height_cm": 160, "bust_in": 36, "waist_in": 30, "hip_in": 39,
                    "fit_preference": "REGULAR", "notes": "Prefers loose sleeves" },
  "channel": "WHATSAPP", "idempotency_key": "wa:919876543210:2026-10-17T18:06:00" }
```

- `measurements` is **optional**. Every field in it is optional, with the same ranges as `POST /customers/{id}/measurements`; `size_code` must be in the size chart when given.
  - If `measurements` is sent, it is stored as the order line's measurements snapshot, with `"source": "order_request"`.
  - Otherwise the customer's latest saved profile is used (`"source": "saved_profile"`), or `null` if there is none.
- The delivery pincode must be serviceable; otherwise the response is 422 `PINCODE_NOT_SERVICEABLE`. The order stores its `estimated_delivery_date` at creation, so it doesn't change later.

- `channel` is `WHATSAPP`, `INSTAGRAM`, `WEB` or `SHOP`; `qty` is 1 to 10.
- **Idempotency:** generate one `idempotency_key` per order attempt and reuse it on every retry. A repeat returns the **original** order with **200** instead of 201, and no duplicate is created, even for two requests at the same moment. Using the same key for a different customer returns 409 `IDEMPOTENCY_KEY_REUSED`.
- The order line stores a copy of the design code and name, the chosen options with their prices, and the customer's latest measurements. Later changes to the catalog or to the measurements don't affect existing orders.

Response **201**:

```json
{ "order_no": "SB-1001", "status": "DRAFT", "customer_id": 512, "channel": "WHATSAPP",
  "delivery": { "name": "Asha Rao", "phone": "+91 98765 43210", "address_line1": "12 MG Road", "address_line2": "Flat 3B",
                "city": "Hyderabad", "state": "Telangana", "pincode": "500001" },
  "items": [ { "design_code": "KUR-101", "design_name": "Anarkali Kurta - Rose", "size_code": "M",
               "options": [ { "option_id": 6, "option_group": "SLEEVE", "name": "Full sleeve", "price_delta": 150.00 },
                            { "option_id": 10, "option_group": "NECK", "name": "Sweetheart neck", "price_delta": 200.00 } ],
               "measurements": { "source": "saved_profile", "measurement_id": 77, "size_code": "M", "height_cm": 160.0, "bust_in": 36.0,
                                 "waist_in": 30.0, "hip_in": 39.0, "fit_preference": "REGULAR", "notes": null,
                                 "recorded_at": "2026-10-17T18:05:40" },
               "unit_price": 2249.00, "qty": 1, "line_total": 2249.00 } ],
  "subtotal": 2249.00, "delivery_charge": 100.00, "total": 2349.00, "currency": "INR",
  "estimated_delivery_date": "2026-10-29", "created_at": "2026-10-17T18:06:02" }
```

`measurements` is `null` if the customer had no measurements when the order was placed.

### `GET /api/agent/orders?status=DRAFT&limit=20`

Recent orders, newest first, for staff/admin use and testing. `status` is optional and not case-sensitive: `DRAFT`, `PAID`, `IN_STITCHING`, `DISPATCHED`, `DELIVERED` or `CANCELLED`; an unknown value returns 400. `limit` can be 1 to 100 (default 20).

```json
[ { "order_no": "SB-1001", "status": "DRAFT", "channel": "WHATSAPP", "customer_id": 512, "customer_name": "Asha Rao",
    "customer_phone": "+919876543210", "design_code": "KUR-101", "design_name": "Anarkali Kurta - Rose", "size_code": "M",
    "qty": 1, "total": 2349.00, "currency": "INR", "estimated_delivery_date": "2026-10-29",
    "created_at": "2026-10-17T18:06:02" } ]
```

### Staff endpoints for mybill-ui (app login, not Google tokens)

The **Boutique Orders** page in mybill-ui reads the same data through endpoints that use the normal app login (`Authorization: Bearer <app JWT>`, tenant = the logged-in firm). They are read-only:

- `GET /api/boutique-orders?status=&limit=50` returns the same rows as above.
- `GET /api/boutique-orders/{orderNo}` returns the same shape as the order response.

A superadmin token without a firm gets 403. Google ID tokens are not accepted here.

### `GET /api/agent/orders/{orderNo}`

Returns the order in the same shape as the create response (`SB-1001`; not case-sensitive). Returns 404 `ORDER_NOT_FOUND` if it doesn't exist.

## Data model notes

The tables are created in each firm schema by `BoutiqueSchemaInitializer`, from `db/boutique/V1..V4`. That happens on startup, and also on the first agent call for a firm registered later.

| Table | Notes |
|---|---|
| `boutique_designs` | The existing designs table, shared with the admin UI and the ecom shop. New columns: `code` (unique), `name`, `fabric_description`, `fabric_product_id`. `rough_price` is the base price and `delivery_days` the lead time. Existing designs got codes like `LEH-1`, and designs created in the UI get one automatically. |
| `boutique_design_images` | Gets a new `sort_order` column. |
| `customisation_options` | New. `design_id` NULL means the option applies to all designs. At most one option per `option_group` on an order line. |
| `size_chart` | New. Filled with a standard XS–XXL chart, but only if the table is empty. |
| `customers` | Gets new columns `phone_e164` and `instagram_user_id` (both unique where not empty), `source` (existing rows are `SHOP`) and `updated_at`. `phone_e164` was filled from the old phone column wherever the result was unambiguous. Numbers shared by several customers were left empty, and the startup log says how many. |
| `customer_measurements` | Gets new columns `size_code`, `height_cm`, `fit_preference`, `source`. The API's `bust_in` is stored in the existing `chest` column. |
| `boutique_orders`, `boutique_order_items` | New. Order numbers come from `boutique_order_no_seq` and start at SB-1001. `estimated_delivery_date` is added by V4. `stitching_order_id` is for linking a paid order to the workshop's stitching order later. |

Sample data for testing (`KUR-101..103` plus options) is in `src/main/resources/db/boutique/dev-seed.sql`. It is **not** run automatically:

```bash
psql "postgresql://neondb_owner:…@ep-mute-sun-amclpke1.c-5.us-east-1.aws.neon.tech/neondb?sslmode=require" \
  -v schema=firm_srisa -f src/main/resources/db/boutique/dev-seed.sql
```

Active designs also appear in the public ecom shop, so remove the samples again afterwards; the clean-up statements are at the bottom of the file.
