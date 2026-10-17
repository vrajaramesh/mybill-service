-- Made-to-order boutique orders placed through the AI agent (separate from the stitching_orders
-- workshop queue). Prices, option names and measurements are snapshotted on the order item, so later
-- catalog changes never alter an existing order.

CREATE SEQUENCE IF NOT EXISTS boutique_order_no_seq START WITH 1001;

CREATE TABLE IF NOT EXISTS boutique_orders (
    order_id               SERIAL        PRIMARY KEY,
    order_no               VARCHAR(20)   NOT NULL,
    customer_id            INTEGER       NOT NULL REFERENCES customers (customer_id),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    delivery_name          VARCHAR(100)  NOT NULL,
    delivery_phone         VARCHAR(20)   NOT NULL,
    delivery_address_line1 VARCHAR(200)  NOT NULL,
    delivery_address_line2 VARCHAR(200),
    delivery_city          VARCHAR(100)  NOT NULL,
    delivery_state         VARCHAR(100)  NOT NULL,
    delivery_pincode       VARCHAR(6)    NOT NULL,
    subtotal               NUMERIC(10,2) NOT NULL,
    delivery_charge        NUMERIC(10,2) NOT NULL,
    total                  NUMERIC(10,2) NOT NULL,
    channel                VARCHAR(20)   NOT NULL,
    idempotency_key        VARCHAR(100)  NOT NULL,
    -- Set when a paid order is handed to the workshop as a stitching order (not automated yet).
    stitching_order_id     INTEGER       REFERENCES stitching_orders (order_id) ON DELETE SET NULL,
    created_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_boutique_orders_order_no UNIQUE (order_no),
    CONSTRAINT uq_boutique_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_boutique_orders_status
        CHECK (status IN ('DRAFT', 'PAID', 'IN_STITCHING', 'DISPATCHED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_boutique_orders_channel CHECK (channel IN ('WHATSAPP', 'INSTAGRAM', 'WEB', 'SHOP'))
);
CREATE INDEX IF NOT EXISTS ix_boutique_orders_customer ON boutique_orders (customer_id);

CREATE TABLE IF NOT EXISTS boutique_order_items (
    item_id               SERIAL        PRIMARY KEY,
    order_id              INTEGER       NOT NULL REFERENCES boutique_orders (order_id) ON DELETE CASCADE,
    design_id             INTEGER       REFERENCES boutique_designs (design_id) ON DELETE SET NULL,
    design_code           VARCHAR(30)   NOT NULL,
    design_name           VARCHAR(150)  NOT NULL,
    size_code             VARCHAR(5)    NOT NULL,
    -- [{"option_id":3,"option_group":"SLEEVE","name":"Full sleeve","price_delta":150.00}, ...]
    options_snapshot      JSONB         NOT NULL DEFAULT '[]',
    measurements_snapshot JSONB,
    unit_price            NUMERIC(10,2) NOT NULL,
    quantity              INTEGER       NOT NULL CHECK (quantity > 0),
    line_total            NUMERIC(10,2) NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_boutique_order_items_order ON boutique_order_items (order_id);
