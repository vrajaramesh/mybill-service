-- WhatsApp numbers whose forwarded supplier posts become dropship collections ("owner numbers"), managed in mybill
-- (Collections → Suppliers → Forwarding numbers). srisa-agent reads the active ones (cached 1 min); the AI never
-- answers these numbers. phone = digits with country code, e.g. 919441249575.
CREATE TABLE IF NOT EXISTS dropship_owner_numbers (
    phone      VARCHAR(15)  PRIMARY KEY,
    label      VARCHAR(60),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
