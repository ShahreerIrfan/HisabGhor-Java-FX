-- SmartKhata Schema Migration V3 (Phase 1: Supply Chain Foundation)
-- Extends suppliers, products pricing/pack model, and creates product_suppliers many-to-many mapping.
-- Preserves all existing V1/V2 rows, balances, and ledgers safely.

-- 1. Supplier enhancements
ALTER TABLE suppliers ADD COLUMN supplier_code TEXT;
ALTER TABLE suppliers ADD COLUMN email TEXT;
ALTER TABLE suppliers ADD COLUMN payment_terms TEXT;
ALTER TABLE suppliers ADD COLUMN credit_period_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE suppliers ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1;

UPDATE suppliers
SET supplier_code = 'SUP-' || printf('%05d', id)
WHERE supplier_code IS NULL OR TRIM(supplier_code) = '';

UPDATE suppliers
SET is_active = CASE WHEN is_archived = 1 THEN 0 ELSE 1 END;

CREATE UNIQUE INDEX IF NOT EXISTS idx_suppliers_code
    ON suppliers(business_id, supplier_code) WHERE supplier_code IS NOT NULL;

-- 2. Product Master TP, MRP & Purchase Unit extensions
-- cost_price_paisha continues as Moving Weighted Average Inventory Cost.
-- selling_price_paisha continues as Default Selling Price.
ALTER TABLE products ADD COLUMN trade_price_paisha INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN mrp_paisha INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN last_purchase_price_paisha INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN purchase_unit TEXT;
ALTER TABLE products ADD COLUMN pack_size INTEGER NOT NULL DEFAULT 1;

UPDATE products
SET trade_price_paisha = CASE WHEN trade_price_paisha = 0 THEN cost_price_paisha ELSE trade_price_paisha END,
    last_purchase_price_paisha = CASE WHEN last_purchase_price_paisha = 0 THEN cost_price_paisha ELSE last_purchase_price_paisha END,
    mrp_paisha = CASE WHEN mrp_paisha = 0 THEN selling_price_paisha ELSE mrp_paisha END,
    purchase_unit = CASE WHEN purchase_unit IS NULL OR TRIM(purchase_unit) = '' THEN unit ELSE purchase_unit END;

-- 3. Product-Supplier Many-to-Many Relationship Table
CREATE TABLE IF NOT EXISTS product_suppliers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    trade_price_paisha INTEGER NOT NULL DEFAULT 0 CHECK (trade_price_paisha >= 0),
    supplier_product_code TEXT,
    min_order_qty INTEGER NOT NULL DEFAULT 1 CHECK (min_order_qty >= 0),
    purchase_unit TEXT NOT NULL DEFAULT 'pcs',
    pack_size INTEGER NOT NULL DEFAULT 1 CHECK (pack_size >= 1),
    is_preferred INTEGER NOT NULL DEFAULT 0 CHECK (is_preferred IN (0, 1)),
    lead_time_days INTEGER NOT NULL DEFAULT 0 CHECK (lead_time_days >= 0),
    last_purchase_price_paisha INTEGER NOT NULL DEFAULT 0 CHECK (last_purchase_price_paisha >= 0),
    last_purchase_date TEXT,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, product_id, supplier_id)
);

CREATE INDEX IF NOT EXISTS idx_product_suppliers_product
    ON product_suppliers(business_id, product_id, is_active);

CREATE INDEX IF NOT EXISTS idx_product_suppliers_supplier
    ON product_suppliers(business_id, supplier_id, is_active);

