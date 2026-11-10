# SmartKhata — Software Requirements Specification (SRS)
**Document Version:** 1.0.0 (Desktop Edition)  
**Target Platform:** Windows 10/11 Standalone Desktop Application  
**Runtime:** Java 21 LTS, JavaFX + AtlantaFX, Embedded SQLite 3  
**Domain:** Offline Small Business Bookkeeping & Inventory Management (Bangladesh MSME / ক্ষুদ্র ও মাঝারি ব্যবসা)

---

## 1. Executive Summary & Purpose

SmartKhata is a native, offline-first desktop bookkeeping and inventory management application designed specifically for retail and wholesale businesses in Bangladesh (e.g., মুদি দোকান, ডিপার্টমেন্টাল স্টোর, পাইকারি আড়ত, ইলেকট্রনিক্স ও স্টেশনারি শপ)। 

Traditional cloud-only or web-based bookkeeping solutions fail in Bangladesh due to erratic internet connectivity, load shedding, recurring cloud subscription costs, and merchants' apprehension regarding remote data privacy. SmartKhata operates **100% offline**, storing all business records in a local, high-performance SQLite database under `%LOCALAPPDATA%`, requiring zero server setup (no MySQL, XAMPP, or external JRE).

---

## 2. Functional Requirements (FR-01 to FR-23)

### FR-01: Business Profile & Store Setup
- **Description:** Allows the merchant to configure business identity, including business name, trade license / registration number, contact phone, shop address, optional logo image, and receipt header text.
- **Desktop Scope:** Managed locally during first-run onboarding or via the Settings module. Persisted in the `business` and `settings` tables.

### FR-02: Authentication & User Credentials (Local Sign-In)
- **Description:** Secure local authentication preventing unauthorized access to business ledgers.
- **Desktop Adaptation:** Replaces the mobile-number OTP authentication specified in cloud architectures. Users authenticate locally using a username/PIN/password. Passwords and PINs are hashed using PBKDF2WithHmacSHA256 / Argon2id with cryptographically random per-user salts.
- **Offline Invariant:** Zero network requests or SMS gateways required to authenticate.

### FR-03: Multi-User Roles & Local Permissions
- **Description:** Support for store roles:
  1. `OWNER`: Full administrative access (financial reports, expense deletion, inventory adjustments, user management, backup/restore).
  2. `CASHIER / STAFF`: Operational access limited to POS sales, payment collections, customer balance lookups, and basic product searching; prohibited from viewing overall profit/loss, modifying past transactions, or restoring backups.
- **Desktop Scope:** Single-active-session local RBAC enforced at the Service layer.

### FR-04: Customer Directory & Party Management
- **Description:** Complete customer CRUD operations: Customer Name (mandatory), Phone Number (optional), Address, Customer Group/Category, Credit Limit, and internal notes.
- **Desktop Scope:** Dedicated Customers view with instant search, pagination/virtualized scrolling, edit dialogs, and soft-delete (archiving) to preserve historical transactions.

### FR-05: Customer Opening Balance & Receivable Ledger (বাকি খাতা)
- **Description:** Track customer receivables ("Baki"). Support setting a verified opening balance upon onboarding.
- **Accounting Rule:** Every credit sale, cash collection, or adjustment automatically creates an immutable customer ledger entry with Running Balance calculation.

### FR-06: Supplier Directory & Vendor Management
- **Description:** Management of wholesale suppliers and vendors: Company Name, Contact Person, Phone, Address, Payment Terms, and Bank/MFS details.
- **Desktop Scope:** Dedicated Suppliers view with search, filter, and transaction history.

### FR-07: Supplier Opening Balance & Payable Ledger (পাওনা খাতা)
- **Description:** Track supplier liabilities ("Paona"). Support opening payable balance entry.
- **Accounting Rule:** Credit purchases, supplier payments, and purchase adjustments post to the supplier ledger with Running Balance calculations.

### FR-08: Product Catalog, Categorization & Pricing
- **Description:** Product catalog supporting Product Name, Barcode / SKU, Category, Unit of Measurement (pcs, kg, litre, box, packet), Cost Price (ক্রয়মূল্য), Selling Price (বিক্রয়মূল্য), and Reorder Threshold.
- **Desktop Scope:** Instant filter by category, barcode scanner keyboard hook, and quick product creation dialog.

### FR-09: Inventory Stock Movement Tracking
- **Description:** Perpetual inventory tracking where stock levels cannot be arbitrarily overwritten.
- **Inventory Ledger:** Every quantity change must originate from an auditable stock movement event:
  - `OPENING_STOCK`
  - `PURCHASE_RECEIPT` (increases stock)
  - `SALE_ISSUE` (decreases stock)
  - `MANUAL_ADJUSTMENT` (damage/spoilage/physical audit with mandatory reason)
  - `RETURN_RECEIPT` / `RETURN_DISPATCH`

### FR-10: Low-Stock Monitoring & Reorder Warnings
- **Description:** Real-time identification of products whose current on-hand quantity is below or equal to their configured reorder threshold.
- **Desktop Scope:** Dashboard widget displaying low-stock items with direct navigation to purchase/supplier workflow.

### FR-11: Point-of-Sale (POS) & Sales Invoicing
- **Description:** High-velocity sales recording supporting:
  - Walk-in cash customer or registered credit customer selection
  - Barcode scanner input and fast keyword search
  - Multi-item cart with quantity, unit selling price, and line-item/bill discounts
  - Payment settlement: Full Cash, Split (Cash + bKash/Nagad), Full Credit (Baki), or Partial Payment
  - Auto-generated business-scoped invoice sequence (e.g., `INV-2025-0001`)
  - Prevention of duplicate submission via UI double-click protection and idempotency keys.

### FR-12: Sale Reversals, Returns & Audit Trail
- **Description:** Processing customer returns or voiding incorrect invoices.
- **Accounting Rule:** Completed invoices are never hard-deleted. A return or void posts an offsetting credit note / adjustment transaction, restores inventory via an auditable movement, adjusts customer receivables, and logs the acting user.

### FR-13: Purchase Recording (Cash & Credit Purchases)
- **Description:** Inward inventory acquisition from suppliers:
  - Supplier selection, Invoice/Challan reference number, Purchase Date
  - Itemized received quantities, unit cost prices, and batch/lot tags if applicable
  - Payment status: Paid immediately, Partial payment, or added to Supplier Payable Ledger.

### FR-14: Purchase Return & Stock Receipts Adjustment
- **Description:** Returning defective/damaged goods to suppliers with corresponding reduction in on-hand inventory and reduction of supplier payables (or receipt of cash refund).

### FR-15: Customer Payment Collection (টাকা আদায়)
- **Description:** Recording payments collected from customers against their outstanding dues:
  - Selection of customer and display of total outstanding balance
  - Payment amount, payment method (Cash, Bank, bKash, Nagad, Rocket), reference/TrxID
  - Deterministic allocation: FIFO (oldest outstanding invoice first) or specific invoice allocation
  - Overpayment handling: explicitly disallowed or recorded as advance customer deposit (credit balance).

### FR-16: Supplier Payment Disbursement (টাকা প্রদান)
- **Description:** Recording disbursements to suppliers against outstanding payables:
  - Selection of supplier, payment date, amount, funding source (Cash drawer or Bank/MFS)
  - Allocation against unpaid purchase bills
  - Real-time update of supplier ledger balance.

### FR-17: Business Operational Expense Tracking (খরচ খাতা)
- **Description:** Recording daily shop expenditures separate from inventory purchases:
  - Categories: Shop Rent (দোকান ভাড়া), Electricity/Utilities (বিদ্যুৎ বিল), Staff Salary (কর্মচারীর বেতন), Transportation (পরিবহন খরচ), Entertainment/Tea (নাস্তা/চা), Maintenance, and Miscellaneous.
  - Recording payee/vendor, notes, amount, and payment method.

### FR-18: Other Business Income Tracking (অন্যান্য আয়)
- **Description:** Recording non-sales revenues (e.g., scrap sale, commission, delivery fee, rental income) to ensure accurate cash reconciliation.

### FR-19: Real-Time Executive Dashboard & Financial KPIs
- **Description:** Visual executive overview matching the design target:
  - Top KPI cards: Total Sales, Due Collection, Total Expenses, Inventory Stock Value
  - Period trend chart: Interactive line chart toggling Sales, Collections, and Expenses
  - Side utility panels: Total Outstanding Dues (Baki) with customer count, Low Stock alerts
  - Recent transactions table: Live chronological stream with type pills and status tags
  - Quick action buttons: Add Sale, Receive Payment, Add Expense, Add Customer.

### FR-20: Financial Statements & Daybook Reporting
- **Description:** Comprehensive analytical reports:
  - Daily Daybook (দৈনিক জমা-খরচ / ক্যাশ খাতা)
  - Sales & Purchase Summary Reports (date-range filterable)
  - Customer Outstanding Aging & Due List
  - Supplier Payable Summary
  - Detailed Customer/Supplier Ledger Statements
  - Net Cash Flow & Estimated Profit/Loss Report (Sales Revenue - Cost of Goods Sold - Expenses + Other Income).

### FR-21: Document Printing & Export (PDF, CSV)
- **Description:** Generation of clean printable thermal receipts (58mm/80mm POS) or A4 invoices, export of ledger statements and summaries to CSV / PDF for physical record-keeping and external audits.

### FR-22: Local Database Backup, Checkpointing & Recovery
- **Description:** Robust offline data safety system:
  - Explicit "Back Up Now" action creating a standalone, coherent backup `.skbak` / `.db` file at a user-selected path (e.g., external USB drive or separate disk partition)
  - Transactionally safe backup using SQLite `VACUUM INTO` or the SQLite Online Backup API to prevent WAL corruption
  - Backup manifest containing application version, schema version, checksum, and creation timestamp
  - Safe restoration workflow with mandatory safety snapshots and foreign key validation.

### FR-23: Localization, Regional Settings & Theming
- **Description:** Culturally tailored settings for Bangladeshi merchants:
  - Full support for English and Bengali (বাংলা) interface terminology
  - Proper South Asian number formatting (e.g., BDT 4,86,530 instead of 486,530)
  - Bengali Unicode typography with robust system fallback
  - AtlantaFX-powered light/dark visual theme support (Light theme as reference target).

---

## 3. Non-Functional Requirements (NFR)

- **NFR-01 (Offline-First Zero Network):** The application must function 100% autonomously without active internet, Wi-Fi, or local network interfaces.
- **NFR-02 (Accounting Precision & Data Integrity):** Zero floating-point rounding errors. All monetary values represented as exact integer Paisha (1 BDT = 100 Paisha) or `BigDecimal` with scale 2 (`RoundingMode.HALF_UP`). Multi-row accounting postings must execute inside atomic SQLite transactions (`BEGIN TRANSACTION ... COMMIT`).
- **NFR-03 (Performance & Responsiveness):** Cold startup time under 2.5 seconds on modern Windows 10/11 machines. Search queries across 50,000 customers or products return within < 50 milliseconds. Dashboard UI maintains smooth 60 FPS transitions.
- **NFR-04 (ACID Durability & Crash Safety):** SQLite configured with `PRAGMA journal_mode = WAL;` and `PRAGMA foreign_keys = ON;` to ensure zero database corruption during sudden power failures or system restarts.
- **NFR-05 (Ergonomics & Keyboard Workflow):** High-efficiency cashier UX with comprehensive keyboard shortcuts (`Ctrl+K` for global search, `F2` for new sale, `F3` for payment collection, `Esc` to dismiss dialogs).
- **NFR-06 (Local Security):** User passwords hashed using PBKDF2 with HMAC-SHA256 (100,000 iterations) or Argon2id with unique salt. Database stored securely in `%LOCALAPPDATA%` under user security context.
- **NFR-07 (Standalone Packaging):** Self-contained Windows installer (.msi / .exe) created via `jpackage`, embedding Java 21 runtime and native SQLite binaries, requiring no prior Java or database installation.

---

## 4. Offline Adaptation & Cloud Boundary Strategy

| Original Cloud Requirement | Desktop Offline Adaptation | Implementation Status |
| :--- | :--- | :--- |
| **Mobile SMS OTP Login** | Local Owner Setup + Master Password / PIN authentication with session locking | Active (Milestone 05) |
| **Remote SMS Notifications** | Offline printable receipts, PDF exports, and on-screen due notices | Replaced by Print/PDF |
| **Cloud Database Sync** | Deferred to future optional module. Data is strictly local SQLite. | Disabled in Phase 1 |
| **Central SaaS Web Admin** | Single-tenant local store administration by the store owner | Adapted to Local RBAC |
| **Online Payment Webhooks** | Merchant records manual Transaction ID (TrxID) for bKash/Nagad | Active manual entry |
| **Cloud Automated Backups** | Transactionally safe local backup to USB flash drive / local folder | Active (Milestone 16) |

