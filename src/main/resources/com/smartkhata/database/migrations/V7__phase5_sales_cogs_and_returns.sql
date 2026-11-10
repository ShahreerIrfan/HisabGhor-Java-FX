-- SmartKhata Schema Migration V7 (Phase 5: Sales COGS, Gross Profit & Partial Sales Returns)

ALTER TABLE sales ADD COLUMN total_cogs_paisha INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN gross_profit_paisha INTEGER NOT NULL DEFAULT 0;

ALTER TABLE sale_items ADD COLUMN gross_profit_paisha INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sale_items ADD COLUMN returned_qty INTEGER NOT NULL DEFAULT 0;

-- Backfill existing historical sales & sale_items without changing prices or quantities
UPDATE sale_items
SET gross_profit_paisha = line_total_paisha - (quantity * unit_cost_paisha);

UPDATE sales
SET total_cogs_paisha = COALESCE((SELECT SUM(si.quantity * si.unit_cost_paisha) FROM sale_items si WHERE si.sale_id = sales.id), 0),
    gross_profit_paisha = net_amount_paisha - COALESCE((SELECT SUM(si.quantity * si.unit_cost_paisha) FROM sale_items si WHERE si.sale_id = sales.id), 0);

CREATE TABLE IF NOT EXISTS sale_returns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    return_number TEXT NOT NULL,
    sale_id INTEGER NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
    customer_id INTEGER REFERENCES customers(id) ON DELETE RESTRICT,
    return_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    total_refund_paisha INTEGER NOT NULL DEFAULT 0 CHECK (total_refund_paisha >= 0),
    total_cogs_restored_paisha INTEGER NOT NULL DEFAULT 0 CHECK (total_cogs_restored_paisha >= 0),
    reason TEXT NOT NULL,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, return_number)
);

CREATE TABLE IF NOT EXISTS sale_return_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_return_id INTEGER NOT NULL REFERENCES sale_returns(id) ON DELETE CASCADE,
    sale_item_id INTEGER NOT NULL REFERENCES sale_items(id) ON DELETE RESTRICT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    returned_qty INTEGER NOT NULL CHECK (returned_qty > 0),
    unit_price_paisha INTEGER NOT NULL CHECK (unit_price_paisha >= 0),
    unit_cost_paisha INTEGER NOT NULL CHECK (unit_cost_paisha >= 0),
    line_refund_paisha INTEGER NOT NULL CHECK (line_refund_paisha >= 0),
    line_cogs_restored_paisha INTEGER NOT NULL CHECK (line_cogs_restored_paisha >= 0)
);

CREATE INDEX IF NOT EXISTS idx_sale_returns_sale ON sale_returns(sale_id);
CREATE INDEX IF NOT EXISTS idx_sale_returns_biz_date ON sale_returns(business_id, return_date);

