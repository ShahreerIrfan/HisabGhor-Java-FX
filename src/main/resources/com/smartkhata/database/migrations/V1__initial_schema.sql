-- ============================================================================
-- SmartKhata Database Migration V1: Initial Core Schema
-- Target: SQLite 3 with Foreign Keys & WAL Mode
-- All monetary units stored as integer BDT Paisha (1 BDT = 100 Paisha)
-- ============================================================================

-- 1. Businesses (Store Profiles)
CREATE TABLE IF NOT EXISTS businesses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    legal_name TEXT,
    phone TEXT,
    address TEXT,
    trade_license TEXT,
    currency_code TEXT NOT NULL DEFAULT 'BDT',
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 2. Local Users & Roles (Single/Multi-User Local RBAC)
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    username TEXT NOT NULL UNIQUE,
    full_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    salt TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'CASHIER')),
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    last_login_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_users_business ON users(business_id, username);

-- 3. Customers (Parties / Baki Khata)
CREATE TABLE IF NOT EXISTS customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    phone TEXT,
    address TEXT,
    credit_limit_paisha INTEGER NOT NULL DEFAULT 0,
    opening_balance_paisha INTEGER NOT NULL DEFAULT 0, -- positive = customer owes business
    is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0, 1)),
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_customers_search ON customers(business_id, is_archived, name);
CREATE INDEX IF NOT EXISTS idx_customers_phone ON customers(business_id, phone);

-- 4. Suppliers (Vendors / Paona Khata)
CREATE TABLE IF NOT EXISTS suppliers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    contact_person TEXT,
    phone TEXT,
    address TEXT,
    opening_balance_paisha INTEGER NOT NULL DEFAULT 0, -- positive = business owes supplier
    is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0, 1)),
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_suppliers_search ON suppliers(business_id, is_archived, name);

-- 5. Product Categories
CREATE TABLE IF NOT EXISTS categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, name)
);

-- 6. Products Catalog
CREATE TABLE IF NOT EXISTS products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    barcode TEXT,
    sku TEXT,
    unit TEXT NOT NULL DEFAULT 'pcs', -- pcs, kg, litre, box, pkt
    cost_price_paisha INTEGER NOT NULL DEFAULT 0 CHECK (cost_price_paisha >= 0),
    selling_price_paisha INTEGER NOT NULL DEFAULT 0 CHECK (selling_price_paisha >= 0),
    low_stock_threshold INTEGER NOT NULL DEFAULT 5 CHECK (low_stock_threshold >= 0),
    is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_products_barcode ON products(business_id, barcode);
CREATE INDEX IF NOT EXISTS idx_products_name ON products(business_id, is_archived, name);

-- 7. Stock Movements (Perpetual Auditable Inventory Ledger)
CREATE TABLE IF NOT EXISTS stock_movements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    movement_type TEXT NOT NULL CHECK (movement_type IN ('OPENING', 'PURCHASE_IN', 'SALE_OUT', 'MANUAL_ADJUSTMENT', 'RETURN_IN', 'RETURN_OUT')),
    quantity INTEGER NOT NULL, -- positive for inward, negative for outward
    unit_cost_paisha INTEGER NOT NULL DEFAULT 0,
    reference_type TEXT CHECK (reference_type IN ('SALE', 'PURCHASE', 'MANUAL', 'RETURN')),
    reference_id INTEGER,
    notes TEXT,
    created_by_user_id INTEGER REFERENCES users(id),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_stock_product_date ON stock_movements(product_id, created_at);

-- 8. Sales Invoices
CREATE TABLE IF NOT EXISTS sales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    invoice_number TEXT NOT NULL UNIQUE,
    customer_id INTEGER REFERENCES customers(id) ON DELETE RESTRICT, -- NULL = Walk-in Cash Customer
    total_amount_paisha INTEGER NOT NULL CHECK (total_amount_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    net_amount_paisha INTEGER NOT NULL CHECK (net_amount_paisha >= 0),
    paid_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (paid_amount_paisha >= 0),
    due_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (due_amount_paisha >= 0),
    payment_status TEXT NOT NULL CHECK (payment_status IN ('PAID', 'PARTIAL', 'BAKI')),
    idempotency_key TEXT UNIQUE,
    invoice_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    notes TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    is_reversed INTEGER NOT NULL DEFAULT 0 CHECK (is_reversed IN (0, 1)),
    reversal_reason TEXT,
    reversed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_sales_business_date ON sales(business_id, invoice_date);
CREATE INDEX IF NOT EXISTS idx_sales_customer_status ON sales(customer_id, payment_status);

-- 9. Sale Line Items
CREATE TABLE IF NOT EXISTS sale_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id INTEGER NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_paisha INTEGER NOT NULL CHECK (unit_price_paisha >= 0),
    unit_cost_paisha INTEGER NOT NULL DEFAULT 0,
    discount_paisha INTEGER NOT NULL DEFAULT 0,
    line_total_paisha INTEGER NOT NULL CHECK (line_total_paisha >= 0)
);
CREATE INDEX IF NOT EXISTS idx_sale_items_product ON sale_items(product_id);

-- 10. Purchases (Supplier Invoices)
CREATE TABLE IF NOT EXISTS purchases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    purchase_number TEXT NOT NULL UNIQUE,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE RESTRICT,
    total_amount_paisha INTEGER NOT NULL CHECK (total_amount_paisha >= 0),
    discount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (discount_paisha >= 0),
    net_amount_paisha INTEGER NOT NULL CHECK (net_amount_paisha >= 0),
    paid_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (paid_amount_paisha >= 0),
    due_amount_paisha INTEGER NOT NULL DEFAULT 0 CHECK (due_amount_paisha >= 0),
    payment_status TEXT NOT NULL CHECK (payment_status IN ('PAID', 'PARTIAL', 'DUE')),
    idempotency_key TEXT UNIQUE,
    purchase_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    notes TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    is_reversed INTEGER NOT NULL DEFAULT 0 CHECK (is_reversed IN (0, 1)),
    reversal_reason TEXT,
    reversed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_purchases_supplier ON purchases(supplier_id, purchase_date);

-- 11. Purchase Line Items
CREATE TABLE IF NOT EXISTS purchase_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_id INTEGER NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_cost_paisha INTEGER NOT NULL CHECK (unit_cost_paisha >= 0),
    line_total_paisha INTEGER NOT NULL CHECK (line_total_paisha >= 0)
);

-- 12. Payments & Collections
CREATE TABLE IF NOT EXISTS payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    payment_number TEXT NOT NULL UNIQUE,
    party_type TEXT NOT NULL CHECK (party_type IN ('CUSTOMER', 'SUPPLIER')),
    party_id INTEGER NOT NULL,
    amount_paisha INTEGER NOT NULL CHECK (amount_paisha > 0),
    payment_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    payment_method TEXT NOT NULL CHECK (payment_method IN ('CASH', 'BANK', 'BKASH', 'NAGAD', 'ROCKET', 'OTHER')),
    reference_trx_id TEXT,
    notes TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    idempotency_key TEXT UNIQUE,
    is_reversed INTEGER NOT NULL DEFAULT 0 CHECK (is_reversed IN (0, 1)),
    reversal_reason TEXT,
    reversed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_payments_party ON payments(business_id, party_type, party_id);
CREATE INDEX IF NOT EXISTS idx_payments_date ON payments(business_id, payment_date);

-- 13. Payment Allocations
CREATE TABLE IF NOT EXISTS payment_allocations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_id INTEGER NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    sale_id INTEGER REFERENCES sales(id) ON DELETE SET NULL,
    purchase_id INTEGER REFERENCES purchases(id) ON DELETE SET NULL,
    allocated_amount_paisha INTEGER NOT NULL CHECK (allocated_amount_paisha > 0),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 14. Customer Ledger (Double-Entry Log for Receivables)
CREATE TABLE IF NOT EXISTS customer_ledger (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    entry_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    entry_type TEXT NOT NULL CHECK (entry_type IN ('OPENING_BALANCE', 'SALE_INVOICE', 'PAYMENT_COLLECTION', 'INVOICE_REVERSAL', 'ADJUSTMENT')),
    reference_id INTEGER,
    debit_paisha INTEGER NOT NULL DEFAULT 0,  -- increases customer debt
    credit_paisha INTEGER NOT NULL DEFAULT 0, -- decreases customer debt
    running_balance_paisha INTEGER NOT NULL,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_cust_ledger_history ON customer_ledger(customer_id, entry_date, id);

-- 15. Supplier Ledger (Double-Entry Log for Payables)
CREATE TABLE IF NOT EXISTS supplier_ledger (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    entry_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    entry_type TEXT NOT NULL CHECK (entry_type IN ('OPENING_BALANCE', 'PURCHASE_BILL', 'PAYMENT_DISBURSEMENT', 'BILL_REVERSAL', 'ADJUSTMENT')),
    reference_id INTEGER,
    credit_paisha INTEGER NOT NULL DEFAULT 0, -- increases supplier payable
    debit_paisha INTEGER NOT NULL DEFAULT 0,  -- decreases supplier payable
    running_balance_paisha INTEGER NOT NULL,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_supp_ledger_history ON supplier_ledger(supplier_id, entry_date, id);

-- 16. Operational Expenses (Daily Business Overhead)
CREATE TABLE IF NOT EXISTS expenses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    category TEXT NOT NULL CHECK (category IN ('RENT', 'UTILITIES', 'SALARY', 'TRANSPORT', 'ENTERTAINMENT', 'MAINTENANCE', 'OTHER')),
    amount_paisha INTEGER NOT NULL CHECK (amount_paisha > 0),
    expense_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    payment_method TEXT NOT NULL CHECK (payment_method IN ('CASH', 'BANK', 'BKASH', 'NAGAD', 'ROCKET', 'OTHER')),
    payee_name TEXT,
    notes TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    is_reversed INTEGER NOT NULL DEFAULT 0 CHECK (is_reversed IN (0, 1)),
    reversal_reason TEXT,
    reversed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_expenses_date ON expenses(business_id, expense_date);

-- 17. Other Business Income
CREATE TABLE IF NOT EXISTS other_incomes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    source_name TEXT NOT NULL,
    amount_paisha INTEGER NOT NULL CHECK (amount_paisha > 0),
    income_date TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    payment_method TEXT NOT NULL,
    notes TEXT,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    is_reversed INTEGER NOT NULL DEFAULT 0 CHECK (is_reversed IN (0, 1)),
    reversal_reason TEXT,
    reversed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_incomes_date ON other_incomes(business_id, income_date);

-- 18. Application Settings & Store Preferences
CREATE TABLE IF NOT EXISTS settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    setting_key TEXT NOT NULL,
    setting_value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(business_id, setting_key)
);

-- 19. Audit Logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    user_id INTEGER REFERENCES users(id),
    action_type TEXT NOT NULL,
    entity_name TEXT NOT NULL,
    entity_id INTEGER,
    change_summary TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_audit_logs ON audit_logs(business_id, created_at);

