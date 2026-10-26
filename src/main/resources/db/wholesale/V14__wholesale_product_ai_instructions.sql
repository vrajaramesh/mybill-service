-- Wholesale module V14: the owner's AI instructions per wholesale product (minimum order, cut lengths, transport,
-- samples, how to answer...). Read by the AI assistants (in-app and WhatsApp) through get_wholesale_product.
ALTER TABLE wholesale_products ADD COLUMN IF NOT EXISTS ai_instructions TEXT;
