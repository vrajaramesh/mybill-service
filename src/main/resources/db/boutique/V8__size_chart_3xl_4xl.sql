-- Plus sizes for the AI agent's size chart: 3XL and 4XL (same steps as XS-XXL: +2" bust/waist/hip, +0.5" shoulder).
INSERT INTO size_chart (size_code, bust_in, waist_in, hip_in, shoulder_in, length_in, sort_order) VALUES
    ('3XL', 44.0, 38.0, 47.0, 16.5, 47.0, 7),
    ('4XL', 46.0, 40.0, 49.0, 17.0, 47.0, 8)
ON CONFLICT (size_code) DO NOTHING;
