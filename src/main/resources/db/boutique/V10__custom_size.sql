-- "CUSTOM" size: no chart row; our tailor calls the customer after payment for exact measurements.
ALTER TABLE boutique_order_items  ALTER COLUMN size_code TYPE VARCHAR(10);
ALTER TABLE customer_measurements ALTER COLUMN size_code TYPE VARCHAR(10);
