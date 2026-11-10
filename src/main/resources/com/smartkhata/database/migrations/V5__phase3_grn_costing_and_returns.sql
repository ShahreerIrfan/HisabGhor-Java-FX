-- SmartKhata Schema Migration V5 (Phase 3: GRN, Inventory Costing & Returns)

CREATE TABLE IF NOT EXISTS goods_receipt_notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    grn_number TEXT NOT NULL,
    purchase_order_id INTEGER REFERENCES purchase_orders(id) ON DELETE RESTRICT,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    receipt_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    status TEXT NOT NULL DEFAULT 'POSTED' CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
    subtotal_paisha INTEGER NOT NULL DEFAULT 0 CHECK (subtotal_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    freight_other_cost_paisha INTEGER NOT NULL DEFAULT 0 CHECK (freight_other_cost_paisha >= 0),
    total_cost_paisha INTEGER NOT NULL DEFAULT 0 CHECK (total_cost_paisha >= 0),
    challan_reference TEXT,
    notes TEXT,
    idempotency_key TEXT,
    posted_by_user_id INTEGER NOT NULL REFERENCES users(id),
    posted_at TEXT,
    reversed_by_user_id INTEGER REFERENCES users(id),
    reversal_reason TEXT,
    reversed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, grn_number),
    UNIQUE(business_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_grn_business_date ON goods_receipt_notes(business_id, receipt_date);
CREATE INDEX IF NOT EXISTS idx_grn_po ON goods_receipt_notes(purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_grn_supplier ON goods_receipt_notes(supplier_id, status);

CREATE TABLE IF NOT EXISTS goods_receipt_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    grn_id INTEGER NOT NULL REFERENCES goods_receipt_notes(id) ON DELETE CASCADE,
    po_item_id INTEGER REFERENCES purchase_order_items(id) ON DELETE RESTRICT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    ordered_qty INTEGER NOT NULL DEFAULT 0,
    received_qty INTEGER NOT NULL CHECK (received_qty >= 0),
    accepted_qty INTEGER NOT NULL CHECK (accepted_qty >= 0),
    rejected_qty INTEGER NOT NULL DEFAULT 0 CHECK (rejected_qty >= 0),
    damaged_qty INTEGER NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    purchase_unit TEXT NOT NULL DEFAULT 'pcs',
    pack_size INTEGER NOT NULL DEFAULT 1 CHECK (pack_size >= 1),
    accepted_base_qty INTEGER NOT NULL DEFAULT 0 CHECK (accepted_base_qty >= 0),
    unit_trade_price_paisha INTEGER NOT NULL DEFAULT 0 CHECK (unit_trade_price_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    allocated_extra_cost_paisha INTEGER NOT NULL DEFAULT 0 CHECK (allocated_extra_cost_paisha >= 0),
    effective_base_unit_cost_paisha INTEGER NOT NULL DEFAULT 0 CHECK (effective_base_unit_cost_paisha >= 0),
    line_total_paisha INTEGER NOT NULL DEFAULT 0 CHECK (line_total_paisha >= 0),
    batch_or_remark TEXT
);

CREATE INDEX IF NOT EXISTS idx_grn_items_grn ON goods_receipt_items(grn_id);
CREATE INDEX IF NOT EXISTS idx_grn_items_product ON goods_receipt_items(product_id);

CREATE TABLE IF NOT EXISTS inventory_cost_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    grn_id INTEGER REFERENCES goods_receipt_notes(id) ON DELETE SET NULL,
    grn_item_id INTEGER REFERENCES goods_receipt_items(id) ON DELETE SET NULL,
    previous_qty INTEGER NOT NULL,
    previous_avg_cost_paisha INTEGER NOT NULL,
    received_base_qty INTEGER NOT NULL,
    received_unit_cost_paisha INTEGER NOT NULL,
    new_qty INTEGER NOT NULL,
    new_avg_cost_paisha INTEGER NOT NULL,
    changed_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    changed_by_user_id INTEGER REFERENCES users(id),
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_cost_history_product ON inventory_cost_history(product_id, changed_at);

CREATE TABLE IF NOT EXISTS purchase_returns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    return_number TEXT NOT NULL,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    grn_id INTEGER REFERENCES goods_receipt_notes(id) ON DELETE RESTRICT,
    return_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    total_return_paisha INTEGER NOT NULL DEFAULT 0 CHECK (total_return_paisha >= 0),
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, return_number)
);

CREATE TABLE IF NOT EXISTS purchase_return_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_return_id INTEGER NOT NULL REFERENCES purchase_returns(id) ON DELETE CASCADE,
    grn_item_id INTEGER REFERENCES goods_receipt_items(id) ON DELETE RESTRICT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    return_base_qty INTEGER NOT NULL CHECK (return_base_qty > 0),
    unit_cost_paisha INTEGER NOT NULL CHECK (unit_cost_paisha >= 0),
    line_total_paisha INTEGER NOT NULL CHECK (line_total_paisha >= 0),
    notes TEXT
);

ALTER TABLE stock_movements ADD COLUMN source_doc_type TEXT;
ALTER TABLE stock_movements ADD COLUMN source_doc_number TEXT;
ALTER TABLE stock_movements ADD COLUMN grn_id INTEGER REFERENCES goods_receipt_notes(id) ON DELETE SET NULL;

