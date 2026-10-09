-- Wholesale module V4: customer types reduced to WHOLESALE and RETAILER, default RETAILER.
-- V3 allowed BUSINESS / DEALER / DISTRIBUTOR / RETAILER / OTHER; existing rows are mapped:
--   BUSINESS, DEALER, DISTRIBUTOR -> WHOLESALE (bulk / trade buyers)
--   OTHER (and anything unexpected) -> RETAILER (the new default)

ALTER TABLE wholesale_customers DROP CONSTRAINT IF EXISTS ck_wholesale_customers_type;

UPDATE wholesale_customers SET customer_type = 'WHOLESALE'
 WHERE customer_type IN ('BUSINESS', 'DEALER', 'DISTRIBUTOR');
UPDATE wholesale_customers SET customer_type = 'RETAILER'
 WHERE customer_type NOT IN ('WHOLESALE', 'RETAILER');

ALTER TABLE wholesale_customers ALTER COLUMN customer_type SET DEFAULT 'RETAILER';
ALTER TABLE wholesale_customers
    ADD CONSTRAINT ck_wholesale_customers_type CHECK (customer_type IN ('WHOLESALE', 'RETAILER'));
