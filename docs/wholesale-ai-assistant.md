# Wholesale AI Assistant

A controlled, tool-calling assistant that answers wholesale customers (currently on Instagram, plus an in-app test
chat). Application data is the only source of truth. The LLM never touches PostgreSQL; it can only call the backend
tools below. It is separate from retail: it has its own `WholesaleLlmClient` (`wholesale.ai.*` settings) and does
not use the retail chatbot or `ClaudeService`.

## How a turn works (`WholesaleAssistantService.chat`)

```
customer message + recent history + conversation context
        │
        ▼
LLM (Anthropic Messages API, tools) ── tool_use ──▶ WholesaleAssistantTools.execute()  (validated backend calls)
        ▲                                                │
        └────────────── tool_result (JSON) ◀─────────────┘
        │  … up to 8 steps …
        ▼
send_reply {reply, intent, answered, confidence, needsHuman, reason}
        │
        ▼
number check: every number in the reply must appear in a tool result, the context or the customer's own messages
  ├─ ok      → reply sent
  ├─ fails   → the model is told which number was unsupported and gets one retry
  └─ again   → configured "unknown" reply, flagged for staff
```

**Unknown handling.** Information is unknown when it is:
- missing from a tool result;
- listed under `notAvailable` in a tool result;
- returned as an error or as `NOT_FOUND`.

For unknown information the assistant must use the configured sentence. The default is: *"I don't have that
information available right now. Our team can confirm it for you."*

The conversation is flagged **Needs attention** when:
- `answered=false`;
- `confidence=low`;
- `needsHuman=true`;
- the AI fails.

If the AI service is unavailable, Instagram sends its own holding message instead.

## Tools

| Tool | Reads / does |
|---|---|
| `search_wholesale_products(query)` | Matches the name, code, type, fabric type, material, colours or designs, tolerating typos. Returns candidates with `productId`. An empty query lists the products. |
| `get_wholesale_product(productId)` | Returns type, fabric type, material, description, colours, designs, specifications, unit, HSN and GST %. Missing fields are listed under `notAvailable`. |
| `get_wholesale_price(productId, quantity, unit)` | Returns today's slab for that quantity: rate excl. GST, GST %, taxable amount, GST amount, total, all slabs and the minimum order quantity. `requestedStatus` is `PRICED`, `BELOW_MINIMUM`, `NO_PRICE_FOR_QUANTITY` or `UNIT_MISMATCH`. |
| `get_wholesale_stock(productId, quantity)` | In stock / enough for the quantity. The exact quantity is included only when **Tell customers the exact stock quantity** is on. |
| `get_wholesale_minimum_quantity(productId)` | The smallest slab minimum. |
| `get_wholesale_business_profile()` | The Wholesale Business Profile, plus the knowledge base: business hours, ordering process, shipping, payment terms and other information. |
| `get_customer_details()` | Only the customer linked to *this* conversation (name, business, city, state, GSTIN); otherwise `linked=false`. It has no ID parameter, so other customers cannot be read. |
| `create_quotation_draft(items[], notes)` | The only write, and it creates a **DRAFT** quotation for the linked customer, priced from the slabs by `WholesaleQuotationService`. See the rules below. |
| `send_reply(...)` | Ends the turn. |

`create_quotation_draft` rules:
- **Approval:** the draft is issued automatically only if **Issue AI quotations automatically** is on. Otherwise
  staff review and issue it.
- **No linked customer:** nothing is saved and staff are flagged with the requested items.
- **Repeat requests:** the same items within 2 hours are not created twice.
- **Test chat:** quotations are only previewed (nothing is saved).

The tools never expose purchase rate, supplier, other customers or SQL. Inputs are validated: the product must be
active, and the quantity is positive with at most 3 decimals.

## Conversation context and history

- **Context:** each turn returns `context` (`productId`, `productName`, `unit`, `quantity`, `quantityUnit`,
  `quotationRef`). It is updated by the tools and stored per Instagram conversation in
  `wholesale_ig_conversations.assistant_context`. The system prompt states it, so follow-ups such as "100 meters?",
  "Price?" or "Available?" resolve without the product name.
- **History:** the last 30 stored messages are passed in. The assistant keeps the newest ones, up to 16 turns and
  8,000 characters, and labels staff messages as staff. The context survives beyond that window.
- **Test chat:** it is stateless; the browser sends back the history and context it received.

## Knowledge base and rules (Wholesale → AI Assistant → Knowledge & Rules, ADMIN to change)

The settings live in `wholesale_assistant_settings`:
- ordering process;
- shipping / delivery information;
- payment terms;
- business hours;
- other information;
- the unknown reply;
- share exact stock quantity (default off);
- allow quotation drafts (default on);
- auto-issue quotations (default off).

Product knowledge has new fields on wholesale products, edited in Wholesale → Products:
- fabric type;
- available colours (comma separated);
- available designs;
- specifications (one `Name: Value` per line).

## API (`/api/wholesale/assistant`)

| Method | Path | Notes |
|---|---|---|
| POST | `/chat` | `{"message", "history": [{"role": "CUSTOMER\|ASSISTANT\|STAFF", "text"}], "context": {}, "customerId": null}`; a dry run that returns `reply`, `replySource` (`AI` / `UNKNOWN` / `ERROR`), `intent`, `answered`, `confidence`, `needsHuman`, `attentionReason`, `toolCalls[]`, `context` and `quotation` |
| GET / PUT | `/settings` | Knowledge base and rules; PUT is **ADMIN** |

On Instagram, each inbound message stores:
- `intent`;
- `extracted` (`intent`, `answered`, `confidence`, `replySource`);
- `facts` (`toolCalls`, `context`, `quotation`);
- `ai_response`.

## Configuration

```
wholesale.ai.model=claude-sonnet-5-5        # default
wholesale.ai.api-key=                       # default: anthropic.api.key
wholesale.ai.api-url=                       # default: anthropic.api.url
```

## Migration

`V12__wholesale_ai_assistant.sql`:
- adds the product knowledge columns;
- creates `wholesale_assistant_settings`, copying the Phase 11 Instagram "share stock" choice;
- adds `wholesale_ig_conversations.assistant_context`.

The Phase 11 two-step pipeline (`WholesaleCustomerAssistantService`) and `/api/wholesale/instagram/assistant/test`
were replaced by this assistant and `/api/wholesale/assistant/chat`.
