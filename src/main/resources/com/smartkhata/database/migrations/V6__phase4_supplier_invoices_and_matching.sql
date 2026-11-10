-- SmartKhata Schema Migration V6 (Phase 4: Supplier Invoices & 3-Way Matching)

CREATE TABLE IF NOT EXISTS supplier_invoices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    invoice_number TEXT NOT NULL,
    supplier_bill_no TEXT,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    purchase_order_id INTEGER REFERENCES purchase_orders(id) ON DELETE RESTRICT,
    grn_id INTEGER REFERENCES goods_receipt_notes(id) ON DELETE RESTRICT,
    invoice_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    due_date TEXT,
    subtotal_paisha INTEGER NOT NULL DEFAULT 0 CHECK (subtotal_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    tax_paisha INTEGER NOT NULL DEFAULT 0 CHECK (tax_paisha >= 0),
    additional_charges_paisha INTEGER NOT NULL DEFAULT 0 CHECK (additional_charges_paisha >= 0),
    net_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (net_amount_paisha >= 0),
    paid_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (paid_amount_paisha >= 0),
    due_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (due_amount_paisha >= 0),
    payment_status TEXT NOT NULL DEFAULT 'DUE' CHECK (payment_status IN ('PAID', 'PARTIAL', 'DUE')),
    matching_status TEXT NOT NULL DEFAULT 'MATCHED' CHECK (matching_status IN ('MATCHED', 'VARIANCE_APPROVED', 'DIRECT_BILL')),
    variance_paisha INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'VOIDED')),
    notes TEXT,
    idempotency_key TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, invoice_number),
    UNIQUE(business_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_sup_inv_business_date ON supplier_invoices(business_id, invoice_date);
CREATE INDEX IF NOT EXISTS idx_sup_inv_supplier ON supplier_invoices(supplier_id, status, payment_status);

CREATE TABLE IF NOT EXISTS supplier_invoice_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    supplier_invoice_id INTEGER NOT NULL REFERENCES supplier_invoices(id) ON DELETE CASCADE,
    grn_item_id INTEGER REFERENCES goods_receipt_items(id) ON DELETE RESTRICT,
    po_item_id INTEGER REFERENCES purchase_order_items(id) ON DELETE RESTRICT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    invoiced_base_qty INTEGER NOT NULL CHECK (invoiced_base_qty > 0),
    unit_price_paisha INTEGER NOT NULL CHECK (unit_price_paisha >= 0),
    variance_paisha INTEGER NOT NULL DEFAULT 0,
    line_total_paisha INTEGER NOT NULL CHECK (line_total_paisha >= 0),
    notes TEXT
);

CREATE TABLE IF NOT EXISTS grn_invoice_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    grn_id INTEGER NOT NULL REFERENCES goods_receipt_notes(id) ON DELETE RESTRICT,
    supplier_invoice_id INTEGER NOT NULL REFERENCES supplier_invoices(id) ON DELETE CASCADE,
    allocated_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (allocated_amount_paisha >= 0),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(grn_id, supplier_invoice_id)
);

ALTER TABLE payment_allocations ADD COLUMN supplier_invoice_id INTEGER REFERENCES supplier_invoices(id) ON DELETE SET NULL;
ALTER TABLE supplier_ledger ADD COLUMN reference_type TEXT;
ALTER TABLE supplier_ledger ADD COLUMN reference_number TEXT;

