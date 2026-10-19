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

Fields that are empty on the customer are filled in. **Existing values are never overwritten.** The response is **201** if a customer was created and **200** if one was found.

**Merging.** If both `phone_e164` and `instagram_user_id` are sent and they belong to two different customers, the two are merged into one before anything else:

- The **older** customer (lower `customer_id`) is kept. Everything that pointed at the other one is moved to it: measurements, boutique orders, stitching orders, bills and marketing events.
- The other customer row is deleted. The kept one gets both identities, and fills its empty name, phone and consent from the deleted one.
- The merge is logged in `customer_merges`, with the moved row counts and a JSON copy of the deleted row. The response's `merged_customer_id` says which id no longer exists.
- If either customer already has a *different* phone or Instagram id than the one sent, they are **not** merged: 409 `IDENTITY_CONFLICT`.

Other cases where the phone or Instagram id already belongs to a different customer still return 409 (`PHONE_IN_USE` / `INSTAGRAM_IN_USE`).

```json
{ "customer_id": 512, "name": "Asha Rao", "phone_e164": "+919876543210", "instagram_user_id": "17841400000000000",
  "source": "WHATSAPP", "created": false, "merged_customer_id": 731,
  "whatsapp_updates_consent": null, "consent_at": null,
  "created_at": "2026-10-17T18:02:11", "updated_at": "2026-10-18T09:12:40" }
```

### `POST /api/agent/customers/{id}/consent`

`{"whatsapp_updates": true}` or `false`: her answer to "may we send you order updates and offers on WhatsApp?". The latest answer wins and is stored with `consent_at`. `whatsapp_updates_consent` is `null` until she has been asked.

- Response **200**: the customer, as above.
- 400 without `whatsapp_updates`; 404 `CUSTOMER_NOT_FOUND`.

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
- Optional marketing attribution (see [Marketing attribution](#marketing-attribution-click-to-whatsapp-ads--instagram-posts)): `source_type` (`AD` or `IG_MEDIA`) and `source_id` (numeric) come as a pair, plus `ctwa_clid` (max 500 chars). They are stored on the order and returned by `GET /orders/{orderNo}`; omit them for organic conversations.
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

## Payments (Razorpay Payment Links)

mybill owns all payment logic. srisa-agent only asks for a link and receives notifications.

```
POST /api/agent/orders/{orderNo}/payment-link ──▶ Razorpay POST /v1/payment_links ──▶ short_url to the customer
customer pays ──▶ Razorpay ──▶ POST /webhooks/razorpay (HMAC) ──▶ payment paid, order PAID, stitching order
                                                               └──▶ POST {AGENT_EVENTS_URL}/internal/events
Cloud Scheduler (every 10 min) ──▶ POST /internal/jobs/reconcile-payments ──▶ catches missed webhooks
```

**Order statuses only move forward:** `DRAFT → PAYMENT_PENDING → PAID → IN_STITCHING → DISPATCHED → DELIVERED`, and `CANCELLED` only before payment. A late or duplicate webhook can never move an order back.

### `POST /api/agent/orders/{orderNo}/payment-link`

Returns the order's active link (status `created`, not expired). If there isn't one, it creates a Razorpay link:
- **Amount:** the order total from the database, in paise. It never comes from the request.
- **Reference:** `reference_id` is the order number. Replacement links use `SB-1001-2`, `-3`, and so on.
- **Expiry:** `expire_by` = now + `PAYMENT_LINK_EXPIRY_HOURS` (default 24).
- **Other settings:** Razorpay's own SMS and email notifications are off. `notes` carries `order_no`, `customer_id` and `tenant`.

The order moves `DRAFT → PAYMENT_PENDING`. PAID or CANCELLED orders get 409 `ORDER_NOT_PAYABLE`. If Razorpay is down or rejects the request: 503 `PAYMENT_PROVIDER_UNAVAILABLE` or 502 `PAYMENT_PROVIDER_REJECTED`.

```json
{ "order_no": "SB-1001", "order_status": "PAYMENT_PENDING", "status": "created",
  "short_url": "https://rzp.io/i/AbC123", "amount": 2349.00, "currency": "INR",
  "expires_at": "2026-10-19T12:00:00", "paid_at": null, "razorpay_payment_id": null, "needs_review": false }
```

### `GET /api/agent/orders/{orderNo}/payment`

Returns the latest link, in the same shape as above. Returns 404 `PAYMENT_NOT_FOUND` if no link exists yet. Order responses (`GET /orders/{orderNo}`) also include `paid_at` and a `payment` object. Order lists include `payment_status`, `paid_at` and `razorpay_payment_id`.

### Webhook `POST /webhooks/razorpay` (public; called by Razorpay)

1. **Signature:** `X-Razorpay-Signature` must equal `hex(HMAC_SHA256(raw body, RAZORPAY_WEBHOOK_SECRET))`, compared in constant time. Otherwise the response is 400.
2. **Deduplication:** by `x-razorpay-event-id` in `public.razorpay_webhook_events`. An event that was already processed returns 200 without being processed again.
3. **`payment_link.paid`:**
   - The amount paid must equal the requested amount, in INR. **If it doesn't, the payment is not marked paid:** it gets `needs_review` and an ERROR is logged.
   - Otherwise the payment becomes `paid`, the order becomes `PAID`, and a **stitching order** is created in the tailors' Boutique queue.
   - srisa-agent is notified with `order.paid`.
4. **`payment_link.expired` / `.cancelled`:** the link is marked; the order stays `PAYMENT_PENDING`, and srisa-agent is notified. If the link was already paid (events out of order), nothing changes.
5. **Failures and speed:** a processing failure returns 500 so Razorpay retries. Agent notifications run after the commit, in the background (3 tries with backoff), so Razorpay never waits.

**GST invoice: TODO.** The billing module creates retail bills from product line items, which doesn't fit a made-to-order garment, so no bill is created automatically yet.

### `POST /internal/jobs/reconcile-payments` (Cloud Scheduler)

This needs a Google ID token for `RECONCILE_ALLOWED_SA_EMAIL`, with audience `AGENT_API_AUDIENCE`. For every firm, it fetches links that are still `created`, older than 5 minutes and not expired. Any that Razorpay reports as `paid` get the same handling as the webhook; expired or cancelled links are marked too. The response is `{"checked", "marked_paid", "marked_closed", "errors"}`.

### Configuration

| Env var | Notes |
|---|---|
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | Already used by the ecom checkout. **Test keys (`rzp_test_…`) create test links.** |
| `RAZORPAY_WEBHOOK_SECRET` | The secret you enter when creating the webhook in the Razorpay dashboard |
| `PAYMENT_LINK_EXPIRY_HOURS` | Default 24 |
| `PAYMENT_CALLBACK_URL` | Optional "thank you" page the customer is sent to after paying (`callback_method=get`). Empty means Razorpay's own success page. |
| `AGENT_EVENTS_URL` / `AGENT_EVENTS_AUDIENCE` | `https://srisa-agent-276371417285.asia-south1.run.app` for both |
| `RECONCILE_ALLOWED_SA_EMAIL` | Cloud Scheduler's service account |

### One-time setup

```bash
PROJECT=project-0708fb26-e204-4400-ae9
MYBILL=https://mybill-service-276371417285.us-central1.run.app
gcloud services enable cloudscheduler.googleapis.com --project=$PROJECT
gcloud iam service-accounts create mybill-scheduler --display-name="mybill Cloud Scheduler" --project=$PROJECT
gcloud scheduler jobs create http reconcile-boutique-payments --project=$PROJECT --location=us-central1 \
  --schedule="*/10 * * * *" --time-zone="Asia/Kolkata" --http-method=POST \
  --uri="$MYBILL/internal/jobs/reconcile-payments" \
  --oidc-service-account-email=mybill-scheduler@$PROJECT.iam.gserviceaccount.com --oidc-token-audience="$MYBILL"
```

In the **Razorpay Dashboard → Settings → Webhooks → Add New Webhook**:
- URL: `$MYBILL/webhooks/razorpay`
- Secret: the value of `RAZORPAY_WEBHOOK_SECRET`
- Active events: `payment_link.paid`, `payment_link.expired`, `payment_link.cancelled`

Webhooks are configured separately for Test and Live mode.

### Testing with Razorpay test mode

1. **Use test keys.** Locally, `application-local.properties` already has `rzp_test_…` keys and the webhook secret `local-test-webhook-secret`. In production the keys come from GitHub secrets: if they're live keys, links are **real** payment requests.
2. **Create a link** for a DRAFT order, then open the `short_url`. In test mode you can pay with UPI id `success@razorpay`, or the test card `4111 1111 1111 1111` with any future expiry and any CVV.
3. **Webhooks need a public URL.** For local testing, either expose your Mac with `cloudflared tunnel --url http://localhost:8080` and add a **Test mode** webhook pointing at `<tunnel>/webhooks/razorpay`, or simulate the webhook with curl:

   ```bash
   BODY='{"event":"payment_link.paid","payload":{"payment_link":{"entity":{"id":"plink_XXXX","amount_paid":234900,"currency":"INR","notes":{"tenant":"firm_srisa"}}},"payment":{"entity":{"id":"pay_TEST1","created_at":1760700000}}}}'
   SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac local-test-webhook-secret | sed 's/^.* //')
   curl -i -X POST http://localhost:8080/webhooks/razorpay -H "Content-Type: application/json" \
     -H "X-Razorpay-Signature: $SIG" -H "x-razorpay-event-id: evt_local_1" --data-binary "$BODY"
   ```

   Replace `plink_XXXX` with the link id stored in `boutique_order_payments`, and `amount_paid` with the order total in paise.
4. **Check the result:** the order shows **Paid** in mybill-ui → Boutique Orders, and a new stitching order appears in Boutique. Run the reconciliation job by hand with `curl -X POST localhost:8080/internal/jobs/reconcile-payments` (the local profile skips its token check).

## Marketing attribution (Click-to-WhatsApp ads / Instagram posts)

Staff map each Click-to-WhatsApp ad (`AD`, Meta's ad id) or, later, Instagram post (`IG_MEDIA`, media id) to the design it promotes in mybill-ui → **Ad & post links**. When a conversation starts from an ad, the WhatsApp webhook's `referral` carries `source_type`/`source_id` and `ctwa_clid`. The agent then:

1. resolves the link to jump straight to the design,
2. reports a `conversation_started` event,
3. passes `source_type`, `source_id` and `ctwa_clid` on `POST /api/agent/orders`.

mybill-ui → **Ad performance** puts it together.

### `GET /api/agent/marketing-links/resolve?type=AD&id=120210000000000001`

Also `?type=IG_MEDIA&permalink=https://www.instagram.com/p/AbC123/`, e.g. for a post the customer shared.

**200** `{"design_code": "KUR-301", "label": "Mustard kurti reel – Diwali boost"}`.

- `id` or `permalink` is required. With both, `id` is used.
- Permalinks are compared without the query string (`?igsh=…`), fragment, trailing slash and `www.`.
- Only **active** links resolve: unknown and deactivated links both return 404 `MARKETING_LINK_NOT_FOUND`.
- A `type` other than `AD`/`IG_MEDIA`, or neither `id` nor `permalink`, is 400.

### `POST /api/agent/marketing-links/sync-ig-media`

Auto-links Instagram posts to designs from their captions. The body is up to 200 posts as returned by the Instagram Graph API:

```json
[{ "media_id": "17912345678901234", "permalink": "https://www.instagram.com/p/AbC123/",
   "caption": "Festive pick ✨ Code: KUR-301 — DM to order!", "media_type": "IMAGE",
   "timestamp": "2026-10-18T05:00:00+0000" }]
```

For each post:

1. **The design code** is the one right after `Code:` (any case, also `Code -`) if present, otherwise the first `[A-Z]{3}-\d+` in the caption.
2. **If that design exists**, an `IG_MEDIA` link is created or updated:
   - `source_id` is the `media_id` and `label` is the first 60 characters of the caption (whitespace collapsed).
   - It's marked `created_by = AUTO`, and the permalink is stored.
3. **Links staff created or edited** in mybill-ui (`created_by = MANUAL`) are **never changed**. Deactivated AUTO links are updated but stay deactivated.

Response **200**:

```json
{ "created": 2, "updated": 1, "skipped": 3,
  "items": [{ "media_id": "17912345678901234", "result": "created", "design_code": "KUR-301", "reason": null }, …] }
```

`reason` for skipped posts: `invalid_media_id`, `no_design_code`, `design_not_found`, `manual_link`, `unchanged` or `permalink_in_use`. Re-sending the same posts is safe.

### `POST /api/agent/marketing-events`

```json
{ "event_type": "conversation_started", "source_type": "AD", "source_id": "120210000000000001",
  "ctwa_clid": "ARAkLkA8rmlFeiCkt...", "customer_id": 512, "occurred_at": "2026-10-18T10:15:00+05:30" }
```

- `event_type` is optional (only `conversation_started` exists); `ctwa_clid`, `customer_id` (must exist → else 422 `CUSTOMER_NOT_FOUND`) and `occurred_at` (default now) are optional.
- **Idempotent on `ctwa_clid`:** the first report returns **201** `{"event_id": 17, "created": true}`, a repeat returns **200** with the same `event_id` and `"created": false`. Events without a `ctwa_clid` are not deduplicated, so send them once.

### Staff endpoints for mybill-ui (`/api/boutique-marketing`, app login)

| Method | Path | Notes |
|---|---|---|
| GET | `/links` | All links, active first. Includes `design_name`. |
| POST | `/links` | `{source_type, source_id, design_code, label, active?, permalink?}` → 201 (`created_by` MANUAL; a permalink used by another link → 409 `PERMALINK_IN_USE`). `source_id` must be numeric (400), the design must exist (422 `DESIGN_NOT_FOUND`), one link per type + id (409 `MARKETING_LINK_EXISTS`). |
| PUT | `/links/{id}` | Same body; `active` omitted = unchanged. Editing an AUTO link makes it MANUAL, so the Instagram sync leaves it alone from then on. |
| POST | `/links/{id}/deactivate`, `/links/{id}/activate` | Soft delete; deactivated links stop resolving but stay in the report. |
| GET | `/designs` | Design picker: `[{code, name, active}]`. |
| GET | `/report?from=2026-10-01&to=2026-10-18` | IST dates, inclusive, default the last 30 days, at most 366 days. |

The report has one row per link (inactive ones too) plus **Unmapped/other**, and a `total`:

- **conversations:** `conversation_started` events by `occurred_at`.
- **orders:** orders by `created_at`.
- **paid_orders / paid_revenue:** orders with `paid_at` set (PAID and every later status), summing the order `total`.
- **conversion_pct:** paid orders ÷ conversations × 100, 1 decimal; `null` without conversations.

"Unmapped/other" collects ad/post ids that have no link **and** orders without a source (organic). Organic orders have no conversation events, so that row's conversion % is not comparable to the ad rows.

## Data model notes

The tables are created in each firm schema by `BoutiqueSchemaInitializer`, from `db/boutique/V1..V8`. That happens on startup, and also on the first agent call for a firm registered later.

| Table | Notes |
|---|---|
| `boutique_designs` | The existing designs table, shared with the admin UI and the ecom shop. New columns: `code` (unique), `name`, `fabric_description`, `fabric_product_id`. `rough_price` is the base price and `delivery_days` the lead time. Existing designs got codes like `LEH-1`, and designs created in the UI get one automatically. |
| `boutique_design_images` | Gets a new `sort_order` column. |
| `customisation_options` | New. `design_id` NULL means the option applies to all designs. At most one option per `option_group` on an order line. |
| `size_chart` | New. Filled with a standard XS–XXL chart, but only if the table is empty. V8 adds 3XL (44/38/47) and 4XL (46/40/49). |
| `customers` | Gets new columns `phone_e164` and `instagram_user_id` (both unique where not empty), `source` (existing rows are `SHOP`) and `updated_at`. `phone_e164` was filled from the old phone column wherever the result was unambiguous. Numbers shared by several customers were left empty, and the startup log says how many. |
| `customer_measurements` | Gets new columns `size_code`, `height_cm`, `fit_preference`, `source`. The API's `bust_in` is stored in the existing `chest` column. |
| `boutique_order_payments` | V5: one row per Razorpay Payment Link (amount in paise, status, payment id, `needs_review`). Separate from the tailors' `boutique_payments`. |
| `marketing_links` | V6: `source_type` (`AD`/`IG_MEDIA`) + numeric `source_id` (unique together) → `design_code` (FK to `boutique_designs.code`), `label`, `active`. |
| `customer_merges` | V7: audit of customers merged by the agent upsert (`kept_customer_id`, `merged_customer_id`, identities, `moved` row counts, `merged_customer` JSON copy of the deleted row). |
| `marketing_links` (V7) | Adds `permalink` (normalised, unique per type) and `created_by` (`MANUAL` / `AUTO`). `customers` gets `whatsapp_updates_consent` (NULL = never asked) and `consent_at`. |
| `marketing_events` | V6: `conversation_started` events from the agent (`source_type`, `source_id`, `ctwa_clid` unique, `customer_id`, `occurred_at`). |
| `boutique_orders`, `boutique_order_items` | New. V6 adds nullable `source_type`, `source_id`, `ctwa_clid`. V5 adds `PAYMENT_PENDING` and `paid_at`. Order numbers come from `boutique_order_no_seq` and start at SB-1001. `estimated_delivery_date` is added by V4. `stitching_order_id` is for linking a paid order to the workshop's stitching order later. |

Sample data for testing (`KUR-101..103` plus options) is in `src/main/resources/db/boutique/dev-seed.sql`. It is **not** run automatically:

```bash
psql "postgresql://neondb_owner:…@ep-mute-sun-amclpke1.c-5.us-east-1.aws.neon.tech/neondb?sslmode=require" \
  -v schema=firm_srisa -f src/main/resources/db/boutique/dev-seed.sql
```

Active designs also appear in the public ecom shop, so remove the samples again afterwards; the clean-up statements are at the bottom of the file.
