-- Create indexes for benchmark workloads

-- Accounts
CREATE INDEX idx_accounts_username ON accounts(username);
CREATE INDEX idx_accounts_status ON accounts(status);

-- Items
CREATE INDEX idx_items_sku ON items(sku);
CREATE INDEX idx_items_active ON items(is_active);

-- Orders
CREATE INDEX idx_orders_account_created ON orders(account_id, created_at DESC);
CREATE INDEX idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX idx_orders_status ON orders(status);

-- Order lines
CREATE INDEX idx_order_lines_order ON order_lines(order_id);
CREATE INDEX idx_order_lines_item ON order_lines(item_id);

-- Analyze tables after index creation
ANALYZE accounts;
ANALYZE items;
ANALYZE orders;
ANALYZE order_lines;
