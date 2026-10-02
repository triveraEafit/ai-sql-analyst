-- AI SQL Analyst — seed data for the allowlisted e-commerce tables.
-- _(Requirement 2.3: at least 2000 rows total across customers, products, orders, order_items)_
--
-- Deterministic data via generate_series so re-runs produce identical rows and
-- analytical queries ("top customers by spend", "revenue by month",
-- "best-selling products") return meaningful results.
--
-- Idempotency approach: this file assumes schema.sql has just (re)created the
-- tables empty. It TRUNCATEs the four e-commerce tables (restarting identities)
-- before inserting, so running seed.sql on its own is also safe to repeat.
-- The interactions table is intentionally NOT seeded (populated at runtime).
--
-- Approximate distribution (grand total ~3520 rows, comfortably > 2000):
--   customers:   250
--   products:    120
--   orders:      900
--   order_items: ~2250 (1-4 line items per order)

TRUNCATE order_items, orders, products, customers RESTART IDENTITY CASCADE;

-- Customers: 250 rows across a handful of countries.
INSERT INTO customers (name, email, country, created_at)
SELECT
    'Customer ' || g,
    'customer' || g || '@example.com',
    (ARRAY['US','UK','DE','FR','CA','AU','IN','BR'])[1 + (g % 8)],
    DATE '2023-01-01' + ((g % 365) || ' days')::interval
FROM generate_series(1, 250) AS g;

-- Products: 120 rows across categories, prices $5.00-$504.75.
INSERT INTO products (name, category, price, created_at)
SELECT
    'Product ' || g,
    (ARRAY['Electronics','Books','Home','Toys','Clothing','Sports'])[1 + (g % 6)],
    ROUND((5 + (g * 4.15))::numeric, 2),
    DATE '2023-01-01' + ((g % 300) || ' days')::interval
FROM generate_series(1, 120) AS g;

-- Orders: 900 rows spread across 2023-2024 so monthly revenue has variation.
-- Each order is assigned to one of the 250 customers (deterministic spread).
INSERT INTO orders (customer_id, status, order_date, created_at)
SELECT
    1 + (g % 250),
    (ARRAY['PENDING','PAID','SHIPPED','CANCELLED'])[1 + (g % 4)],
    DATE '2023-01-01' + ((g % 730) || ' days')::interval,
    now()
FROM generate_series(1, 900) AS g;

-- Order items: 1-4 line items per order (deterministic count = 1 + (order_id % 4)).
-- Each line references a product and captures the product's price as unit_price,
-- so revenue (quantity * unit_price) aggregates cleanly.
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT
    o.id,
    p.product_id,
    1 + ((o.id + p.line_no) % 5),                              -- quantity 1-5
    (SELECT price FROM products WHERE products.id = p.product_id)
FROM orders o
CROSS JOIN LATERAL (
    SELECT
        line_no,
        1 + ((o.id * 7 + line_no * 13) % 120) AS product_id    -- product 1-120
    FROM generate_series(1, 1 + (o.id % 4)) AS line_no
) AS p;
