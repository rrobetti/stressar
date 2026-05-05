-- Data generation script with configurable sizes
-- Parameters expected to be substituted: num_accounts, num_items, num_orders, avg_lines_per_order
-- Seed for deterministic generation: uses setseed()

-- Set random seed for deterministic data generation
-- Default seed: 0.42 (can be overridden)
SELECT setseed(0.42);

-- Generate accounts
-- Default: 10000 accounts
INSERT INTO accounts (username, email, full_name, balance_cents, status)
SELECT
    'user_' || i,
    'user_' || i || '@example.com',
    'User Number ' || i,
    (random() * 100000)::BIGINT,  -- Random balance 0-1000 dollars
    CASE WHEN random() < 0.95 THEN 1 ELSE 0 END  -- 95% active
FROM generate_series(1, 10000) AS i;

-- Generate items
-- Default: 5000 items
INSERT INTO items (sku, name, description, price_cents, stock_quantity, is_active)
SELECT
    'SKU_' || i,
    'Item ' || i,
    'Description for item ' || i,
    (100 + random() * 9900)::INTEGER,  -- Price between $1 and $100
    (random() * 10000)::INTEGER,  -- Stock 0-10000
    random() < 0.98  -- 98% active
FROM generate_series(1, 5000) AS i;

-- Generate historical orders
-- Default: 50000 orders with avg 3 lines per order
-- This creates a realistic historical dataset
INSERT INTO orders (account_id, created_at, status, total_cents)
SELECT
    1 + (random() * (SELECT COUNT(*) FROM accounts WHERE status = 1))::BIGINT % (SELECT COUNT(*) FROM accounts WHERE status = 1),
    CURRENT_TIMESTAMP - (random() * interval '180 days'),  -- Orders within last 6 months
    CASE 
        WHEN random() < 0.7 THEN 3  -- 70% completed
        WHEN random() < 0.9 THEN 2  -- 20% shipped
        WHEN random() < 0.98 THEN 1  -- 8% confirmed
        ELSE 0  -- 2% pending
    END,
    0  -- Will be updated after inserting lines
FROM generate_series(1, 50000) AS i;

-- Generate order lines
-- Average 3 lines per order (range 1-5)
INSERT INTO order_lines (order_id, line_no, item_id, qty, price_cents)
SELECT
    o.order_id,
    line_no,
    1 + (random() * (SELECT COUNT(*) FROM items WHERE is_active = TRUE))::BIGINT % (SELECT COUNT(*) FROM items WHERE is_active = TRUE),
    (1 + random() * 3)::SMALLINT,  -- Quantity 1-4
    (SELECT price_cents FROM items WHERE is_active = TRUE ORDER BY random() LIMIT 1)  -- Use item price
FROM orders o
CROSS JOIN LATERAL generate_series(1, (1 + random() * 4)::INTEGER) AS line_no;

-- Update order totals based on order lines
UPDATE orders o
SET total_cents = (
    SELECT COALESCE(SUM(ol.qty * ol.price_cents), 0)
    FROM order_lines ol
    WHERE ol.order_id = o.order_id
);

-- Print statistics
SELECT 'Data generation complete' AS status;
SELECT COUNT(*) AS account_count FROM accounts;
SELECT COUNT(*) AS item_count FROM items;
SELECT COUNT(*) AS order_count FROM orders;
SELECT COUNT(*) AS order_line_count FROM order_lines;
SELECT ROUND(AVG(line_count), 2) AS avg_lines_per_order
FROM (
    SELECT order_id, COUNT(*) AS line_count
    FROM order_lines
    GROUP BY order_id
) AS line_counts;
