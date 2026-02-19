-- Database schema for benchmark workloads
-- Tables: accounts, items, orders, order_lines

DROP TABLE IF EXISTS order_lines CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS items CASCADE;
DROP TABLE IF EXISTS accounts CASCADE;

-- Accounts table
CREATE TABLE accounts (
    account_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL,
    full_name VARCHAR(100),
    balance_cents BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status SMALLINT NOT NULL DEFAULT 1  -- 1=active, 0=inactive
);

-- Items table (catalog)
CREATE TABLE items (
    item_id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    price_cents INTEGER NOT NULL,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Orders table
CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(account_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status SMALLINT NOT NULL DEFAULT 0,  -- 0=pending, 1=confirmed, 2=shipped, 3=completed, -1=cancelled
    total_cents BIGINT NOT NULL DEFAULT 0
);

-- Order lines table
CREATE TABLE order_lines (
    order_line_id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    line_no SMALLINT NOT NULL,
    item_id BIGINT NOT NULL REFERENCES items(item_id),
    qty SMALLINT NOT NULL,
    price_cents INTEGER NOT NULL,  -- snapshot of price at order time
    UNIQUE(order_id, line_no)
);

-- Enable pg_stat_statements if available
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
