-- SmartKhata Schema Migration V4 (Phase 2: Purchase Order Management)
-- POs never mutate stock_movements or supplier_ledger payables.

CREATE TABLE IF NOT EXISTS purchase_orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    po_number TEXT NOT NULL,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    po_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    expected_delivery_date TEXT,
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED', 'PARTIALLY_RECEIVED', 'FULLY_RECEIVED', 'CANCELLED', 'CLOSED')),
    subtotal_paisha INTEGER NOT NULL DEFAULT 0 CHECK (subtotal_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    tax_paisha INTEGER NOT NULL DEFAULT 0 CHECK (tax_paisha >= 0),
    additional_charges_paisha INTEGER NOT NULL DEFAULT 0 CHECK (additional_charges_paisha >= 0),
    grand_total_paisha INTEGER NOT NULL DEFAULT 0 CHECK (grand_total_paisha >= 0),
    supplier_reference TEXT,
    notes TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, po_number)
);

CREATE INDEX IF NOT EXISTS idx_po_business_date ON purchase_orders(business_id, po_date);
CREATE INDEX IF NOT EXISTS idx_po_supplier ON purchase_orders(supplier_id, status);

CREATE TABLE IF NOT EXISTS purchase_order_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_order_id INTEGER NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    ordered_qty INTEGER NOT NULL CHECK (ordered_qty > 0),
    received_qty INTEGER NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
    purchase_unit TEXT NOT NULL DEFAULT 'pcs',
    pack_size INTEGER NOT NULL DEFAULT 1 CHECK (pack_size >= 1),
    trade_price_paisha INTEGER NOT NULL DEFAULT 0 CHECK (trade_price_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    tax_paisha INTEGER NOT NULL DEFAULT 0 CHECK (tax_paisha >= 0),
    line_total_paisha INTEGER NOT NULL DEFAULT 0 CHECK (line_total_paisha >= 0),
    notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_po_items_order ON purchase_order_items(purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_po_items_product ON purchase_order_items(product_id);

