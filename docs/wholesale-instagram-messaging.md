# Wholesale Instagram Customer Messaging

Customers message the business's Instagram account. The app stores every message, understands the question with
the AI, answers it from **wholesale product / price-rule / stock data only**, and replies on Instagram. Staff can
read every conversation, reply themselves, and take over from the AI.

This is separate from the retail website chatbot, the WhatsApp webhook and Instagram Reels posting. None of those
were changed.

## Flow

```
Instagram ──webhook──▶ /api/public/{firmCode}/wholesale/instagram/webhook
                         │  check X-Hub-Signature-256 (app secret), store message (dedupe by Instagram "mid")
                         ▼
                 per-customer worker lane (one customer's messages are answered in order)
                         │  human takeover / auto-reply off?  → store only, no reply
                         ▼
   WholesaleAssistantService (Phase 12: tool-calling assistant, see wholesale-ai-assistant.md)
                         │  conversation history + stored context (product / quantity / quotation)
                         ▼
   store intent, tool calls (business data used), reply, context  ──▶  Graph API  POST {base}/me/messages
```

- The AI reaches business data only through controlled backend tools. It never gets database access. Its only
  write is a DRAFT quotation for a linked customer, which staff approve.
- Unknown information gets the configured "I don't have that information…" reply, and the conversation is flagged
  **Needs attention**. Orders, quotation requests, low confidence and attachments are also flagged.
- If the AI service is unavailable, the configured **holding message** is sent.

## Human takeover

- **Take over** (button), a **staff reply from the inbox**, or a reply typed **in the Instagram app** (seen through
  `message_echoes`) switches the conversation's AI off (`ai_enabled = false`, `takeover_by`, `takeover_at`).
- While it is off, customer messages are stored with status `HUMAN_TAKEOVER` and no AI reply is sent. If staff
  take over while the AI is still writing, its reply is discarded.
- **Re-enable AI** turns it back on for new messages. Messages that arrived during the takeover are not answered.
- The global switch **AI auto-reply** (Settings) turns off all automatic replies (status `AI_DISABLED`).

## Meta setup

1. Use an Instagram **professional** account connected to a Meta app that has Instagram messaging permission
   (`instagram_manage_messages` with a Page token, or `instagram_business_manage_messages` with Instagram Login).
2. In MyBill: **Wholesale → Instagram Inbox → Settings**. Fill in:
   - the verify token (use Generate);
   - the app secret;
   - the access token;
   - the account ID (optional);
   - the API base URL: `https://graph.facebook.com/v21.0`, or `https://graph.instagram.com/v21.0` for an
     Instagram-login token.

   Then tick **Receive Instagram messages** and save. Only Graph API hosts are accepted for the base URL, because
   the token is sent there.
3. In the Meta app's webhook settings:
   - set the callback URL shown in Settings (`https://<api-host>/api/public/<firmCode>/wholesale/instagram/webhook`);
   - use the same verify token;
   - subscribe to `messages` and `message_echoes`.
4. Use **Wholesale → AI Assistant → Test Chat** to check the replies against your products before going live.

Instagram allows replies only within **24 hours** of the customer's last message. The inbox warns when that window
has closed. A failed send is kept as `FAILED`, with Instagram's error.

## API (`/api/wholesale/instagram`, firm JWT)

| Method | Path | Notes |
|---|---|---|
| GET | `/conversations?q=&filter=ATTENTION\|TAKEOVER\|UNREAD` | Inbox (needs-attention first, then newest) |
| GET | `/conversations/{id}` | Conversation and all messages, including intent, extracted data, facts and AI reply |
| POST | `/conversations/{id}/messages` | `{"text": "…"}` staff reply; turns on takeover; returns the message (`SENT` / `FAILED`) |
| POST | `/conversations/{id}/takeover` | Disable AI for this conversation |
| POST | `/conversations/{id}/release` | Re-enable AI |
| POST | `/conversations/{id}/read` | Unread count → 0 |
| POST | `/conversations/{id}/resolve` | Clear "needs attention" |
| PUT | `/conversations/{id}/customer` | `{"customerId": 12}` map to a wholesale customer (`null` = unlink) |
| GET / PUT | `/settings` | PUT is **ADMIN**; token and app secret are write-only (`null` = keep, `""` = clear) |

Public webhook: `GET` (handshake: `hub.mode=subscribe`, `hub.verify_token`, `hub.challenge`) and `POST`
`/api/public/{firmCode}/wholesale/instagram/webhook`. The POST returns:
- `200` when the delivery is stored, or ignored because messaging is disabled;
- `403` for a bad signature;
- `500` when storing failed, so Meta retries (retries are deduplicated).

## Database (`V11__wholesale_instagram_messaging.sql`)

| Table | Purpose |
|---|---|
| `wholesale_instagram_settings` | Single row: enabled, auto-reply, account ID, API base URL, access token, app secret, verify token, holding message. Since V12, "share exact stock" is read from `wholesale_assistant_settings`. |
| `wholesale_ig_conversations` | One per Instagram user ID: mapped `wholesale_customer_id`, `ai_enabled` / takeover, needs attention and reason, unread count, `assistant_context` (V12), last message, last inbound time (24-hour window) |
| `wholesale_ig_messages` | Instagram message ID (unique), direction, sender (CUSTOMER / AI / STAFF), text, attachments, intent, extracted data, facts, AI response, reply message ID, status, error, sent by, timestamp |

Inbound statuses:
- `RECEIVED`, then `PROCESSING`;
- then one of `REPLIED`, `NEEDS_HUMAN` (attachment), `HUMAN_TAKEOVER`, `AI_DISABLED` or `FAILED`.

Outbound statuses: `PENDING`, then `SENT` or `FAILED`.

## Limitations

- **Text only:** images and stickers are stored and flag the conversation, but they are not analysed.
- **Lost on restart:** processing runs in memory after the message is stored. A restart during the few seconds
  of AI processing leaves that message as `RECEIVED` / `PROCESSING`, and staff can reply manually.
- **Plain-text secrets:** the token and app secret are stored in the firm schema in plain text, like the existing
  WhatsApp settings, and are never returned by the API.
- **Wording is not checked:** the number check stops invented prices and quantities. Wording-level claims are
  limited by the prompt only, for example "we will notify you". Every reply, and the facts it was built from, is
  stored for review.
