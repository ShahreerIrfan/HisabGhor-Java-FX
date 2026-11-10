package com.smartkhata.service.sample;

import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.vo.Money;
import com.smartkhata.util.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Seeds sample reference and demo datasets across ALL 19 SQLite database tables.
 * Guarantees visual realism, complete CRUD testing availability, and exact test suite compatibility.
 */
public final class SampleDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SampleDataSeeder() {}

    /**
     * Seeds reference dataset matching docs/dashboard-reference.png (January 2025)
     * if the database is currently empty.
     */
    public static synchronized void seedReferenceDataIfEmpty() {
        TransactionManager.runInTransaction(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM businesses;")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    log.debug("Database already contains data; skipping reference data seeding.");
                    return;
                }
            }

            log.info("Seeding reference dataset matching docs/dashboard-reference.png across all 19 tables...");

            // 1. Table: businesses (Mizan Stores)
            long businessId;
            String sqlBiz = "INSERT INTO businesses (name, legal_name, phone, address, currency_code) " +
                    "VALUES ('Mizan Stores', 'Mizan Enterprise Ltd.', '01711223344', 'Dhaka, Bangladesh', 'BDT')";
            try (PreparedStatement ps = conn.prepareStatement(sqlBiz, Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    businessId = rs.getLong(1);
                }
            }

            // 2. Table: users (mizan as OWNER, karim as CASHIER - PIN 1234)
            PasswordHasher.HashedCredential mizanCred = PasswordHasher.hashPassword("1234".toCharArray());
            long userId;
            String sqlUser = "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                    "VALUES (?, 'mizan', 'Mizanur Rahman', ?, ?, 'OWNER')";
            try (PreparedStatement ps = conn.prepareStatement(sqlUser, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, businessId);
                ps.setString(2, mizanCred.hashHex());
                ps.setString(3, mizanCred.saltHex());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }

            PasswordHasher.HashedCredential karimCred = PasswordHasher.hashPassword("1234".toCharArray());
            long cashierUserId;
            String sqlCashier = "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                    "VALUES (?, 'karim', 'Karim Ullah (Cashier)', ?, ?, 'CASHIER')";
            try (PreparedStatement ps = conn.prepareStatement(sqlCashier, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, businessId);
                ps.setString(2, karimCred.hashHex());
                ps.setString(3, karimCred.saltHex());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    cashierUserId = rs.getLong(1);
                }
            }

            // 3. Table: categories
            long catOil = insertCategory(conn, businessId, "Cooking Oil & Ghee", "Edible oils, mustard oil, soybean oil & pure ghee");
            long catRice = insertCategory(conn, businessId, "Rice & Grains", "Aromatic, miniket, nazirshail & basmati rice");
            long catSpices = insertCategory(conn, businessId, "Salt, Sugar & Spices", "Iodized salt, packaged sugar & essential spices");
            long catDet = insertCategory(conn, businessId, "Detergent & Home Care", "Washing powder, dish soap & surface cleaners");
            long catFlour = insertCategory(conn, businessId, "Flour & Bakery", "Atta, maida, suji & bakery ingredients");

            // 4. Table: suppliers
            long suppMeghna = insertSupplier(conn, businessId, "Meghna Wholesale Traders", "01811223344", "Badamtoli, Dhaka", "Al-Hajj Rafiqul Islam");
            long suppSquare = insertSupplier(conn, businessId, "Square Consumer Distributors", "01799887766", "Mohakhali C/A, Dhaka", "Kabir Hossain");
            long suppBashundhara = insertSupplier(conn, businessId, "Bashundhara Agro Supply", "01611002233", "Baridhara, Dhaka", "Tareq Aziz");

            // 5. Table: supplier_ledger (Opening payables)
            insertSupplierLedgerEntry(conn, businessId, suppMeghna, "2025-01-01 09:00:00", "OPENING_BALANCE", 0L, 50000 * 100L, 50000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, suppSquare, "2025-01-01 09:00:00", "OPENING_BALANCE", 0L, 35000 * 100L, 35000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, suppBashundhara, "2025-01-01 09:00:00", "OPENING_BALANCE", 0L, 20000 * 100L, 20000 * 100L);

            // 6. Table: customers
            long custRahman = insertCustomer(conn, businessId, "Rahman Trading", "01710000001", Money.ofBdt(100000));
            long custKarim = insertCustomer(conn, businessId, "Karim Enterprise", "01710000002", Money.ofBdt(150000));
            long custBashundhara = insertCustomer(conn, businessId, "Bashundhara Supply", "01710000003", Money.ofBdt(200000));
            long custShuvo = insertCustomer(conn, businessId, "Shuvo General Store", "01710000004", Money.ofBdt(50000));
            long custAhsan = insertCustomer(conn, businessId, "Ahsan Trading", "01710000005", Money.ofBdt(80000));

            // Remaining customers to produce 12 customers with due = BDT 3,27,600
            long remainingDues = 327600L - (18750L + 50000L);
            for (int i = 6; i <= 12; i++) {
                long cId = insertCustomer(conn, businessId, "Baki Merchant " + i, "017000000" + i, Money.ofBdt(50000));
                long share = remainingDues / 7;
                insertCustomerLedgerEntry(conn, businessId, cId, "2025-01-20 10:00:00", "OPENING_BALANCE", share * 100L, 0L, share * 100L);
            }

            // 7. Table: products (with image_path & category_id)
            long pOil = insertProductWithImage(conn, businessId, catOil, "Sunflower Oil (5 Ltr)", "pcs", 850, 950, 10, "/com/smartkhata/ui/images/pos/cooking_oil.png", "8901001001");
            long pDet = insertProductWithImage(conn, businessId, catDet, "Aci Detergent (1 kg)", "pcs", 110, 130, 15, "/com/smartkhata/ui/images/pos/lentil_bowl.png", "8901001002");
            long pSalt = insertProductWithImage(conn, businessId, catSpices, "Power Salt (1 kg)", "pcs", 35, 42, 20, "/com/smartkhata/ui/images/pos/salt_pack.png", "8901001003");
            long pBulk = insertProductWithImage(conn, businessId, catRice, "Premium Basmati Rice (50 kg)", "bag", 4000, 4500, 5, "/com/smartkhata/ui/images/pos/rice_bowl.png", "8901001004");
            long pSugar = insertProductWithImage(conn, businessId, catSpices, "Sugar Deshi Pack (1 kg)", "pcs", 120, 140, 15, "/com/smartkhata/ui/images/pos/sugar_bowl.png", "8901001005");
            long pFlour = insertProductWithImage(conn, businessId, catFlour, "Fresh Maida Flour (2 kg)", "pcs", 130, 160, 10, "/com/smartkhata/ui/images/pos/flour_sack.png", "8901001006");

            // 8. Table: stock_movements (Opening stocks)
            insertStockMovement(conn, businessId, pOil, "OPENING", 5, 850 * 100L, userId);
            insertStockMovement(conn, businessId, pDet, "OPENING", 8, 110 * 100L, userId);
            insertStockMovement(conn, businessId, pSalt, "OPENING", 10, 35 * 100L, userId);
            insertStockMovement(conn, businessId, pBulk, "OPENING", 217, 4000 * 100L, userId);
            insertStockMovement(conn, businessId, pSugar, "OPENING", 40, 120 * 100L, userId);
            insertStockMovement(conn, businessId, pFlour, "OPENING", 35, 130 * 100L, userId);

            // 9. Tables: purchases & purchase_items
            long pur1 = insertPurchase(conn, businessId, "PUR-2025-001", suppMeghna, 85000 * 100L, 50000 * 100L, 35000 * 100L, "PARTIAL", "2025-01-08 11:00:00", userId);
            insertPurchaseItem(conn, pur1, pOil, 100, 850 * 100L, 85000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, suppMeghna, "2025-01-08 11:00:00", "PURCHASE_BILL", 0L, 85000 * 100L, 135000 * 100L);

            long pur2 = insertPurchase(conn, businessId, "PUR-2025-002", suppSquare, 45000 * 100L, 45000 * 100L, 0L, "PAID", "2025-01-12 14:30:00", userId);
            insertPurchaseItem(conn, pur2, pDet, 409, 110 * 100L, 45000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, suppSquare, "2025-01-12 14:30:00", "PURCHASE_BILL", 0L, 45000 * 100L, 80000 * 100L);

            long pur3 = insertPurchase(conn, businessId, "PUR-2025-003", suppBashundhara, 120000 * 100L, 60000 * 100L, 60000 * 100L, "PARTIAL", "2025-01-18 16:00:00", userId);
            insertPurchaseItem(conn, pur3, pBulk, 30, 4000 * 100L, 120000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, suppBashundhara, "2025-01-18 16:00:00", "PURCHASE_BILL", 0L, 120000 * 100L, 140000 * 100L);

            // 10. Tables: sales & sale_items
            long sale1 = insertSale(conn, businessId, "INV-2025-001", custRahman, 25340 * 100L, 25340 * 100L, 0L, "PAID", "2025-01-31 11:24:00", userId);
            insertSaleItem(conn, sale1, pOil, 20, 950 * 100L, 850 * 100L, 0L, 19000 * 100L);
            insertSaleItem(conn, sale1, pSugar, 45, 140 * 100L, 120 * 100L, 0L, 6340 * 100L);

            // Jan 30, 2025 05:38 PM - Shuvo General Store - Sale BDT 18,750 (Baki)
            long saleShuvo = insertSale(conn, businessId, "INV-2025-002", custShuvo, 18750 * 100L, 0L, 18750 * 100L, "BAKI", "2025-01-30 17:38:00", userId);
            insertSaleItem(conn, saleShuvo, pBulk, 4, 4500 * 100L, 4000 * 100L, 0L, 18000 * 100L);
            insertSaleItem(conn, saleShuvo, pSalt, 17, 42 * 100L, 35 * 100L, 0L, 750 * 100L);
            insertCustomerLedgerEntry(conn, businessId, custShuvo, "2025-01-30 17:38:00", "SALE_INVOICE", 18750 * 100L, 0L, 18750 * 100L);

            // Jan 30, 2025 04:20 PM - Ahsan Trading - Sale BDT 32,400 (Paid)
            long sale3 = insertSale(conn, businessId, "INV-2025-003", custAhsan, 32400 * 100L, 32400 * 100L, 0L, "PAID", "2025-01-30 16:20:00", userId);
            insertSaleItem(conn, sale3, pOil, 30, 950 * 100L, 850 * 100L, 0L, 28500 * 100L);
            insertSaleItem(conn, sale3, pFlour, 24, 160 * 100L, 130 * 100L, 0L, 3900 * 100L);

            // 11. Tables: payments & payment_allocations
            long payCust1 = insertPayment(conn, businessId, "PAY-2025-001", "CUSTOMER", custKarim, 50000 * 100L, "2025-01-31 10:47:00", "BKASH", userId);
            insertPaymentAllocation(conn, payCust1, null, null, 50000 * 100L);
            insertCustomerLedgerEntry(conn, businessId, custKarim, "2025-01-31 10:47:00", "PAYMENT_COLLECTION", 0L, 50000 * 100L, 0L);

            long paySupp1 = insertPayment(conn, businessId, "PAY-SUP-001", "SUPPLIER", suppMeghna, 50000 * 100L, "2025-01-08 12:00:00", "BANK", userId);
            insertPaymentAllocation(conn, paySupp1, null, pur1, 50000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, suppMeghna, "2025-01-08 12:00:00", "PAYMENT_DISBURSEMENT", 50000 * 100L, 0L, 85000 * 100L);

            // Additional trend sales
            int[] daySales = { 15000, 18000, 22000, 25000, 28000, 31000, 29000, 33000, 37000, 34000,
                    36000, 42000, 39000, 46000, 41000, 47000, 43000, 52000, 58000 };
            for (int i = 0; i < daySales.length; i++) {
                int day = 1 + (i * 30 / daySales.length);
                String dateStr = String.format("2025-01-%02d 14:00:00", Math.min(day, 29));
                long tSale = insertSale(conn, businessId, "INV-TREND-" + i, custRahman, daySales[i] * 100L, daySales[i] * 100L, 0L, "PAID", dateStr, userId);
                insertSaleItem(conn, tSale, pOil, Math.max(1, daySales[i] / 950), 950 * 100L, 850 * 100L, 0L, daySales[i] * 100L);
            }

            // Remaining collection for BDT 1,92,750
            long remainingCollection = 192750L - 50000L;
            long payCust2 = insertPayment(conn, businessId, "PAY-2025-002", "CUSTOMER", custKarim, remainingCollection * 100L, "2025-01-15 11:00:00", "CASH", userId);
            insertPaymentAllocation(conn, payCust2, null, null, remainingCollection * 100L);

            // 12. Table: expenses
            insertExpense(conn, businessId, "UTILITIES", 12500 * 100L, "2025-01-31 10:15:00", "Bashundhara Supply", userId);
            long remainingExpense = 136420L - 12500L;
            insertExpense(conn, businessId, "RENT", remainingExpense * 100L, "2025-01-05 09:00:00", "Shop Landlord", userId);

            // 13. Table: other_incomes
            insertOtherIncome(conn, businessId, "Carton & Gunny Bag Scrap Sale", 4500 * 100L, "2025-01-22 15:30:00", "CASH", "Scrap packaging sale", userId);
            insertOtherIncome(conn, businessId, "Distributor Quarterly Volume Rebate", 8200 * 100L, "2025-01-28 16:45:00", "BANK", "Manufacturer rebate incentive", userId);

            // 14. Table: settings
            insertSetting(conn, businessId, "pref.theme", "LIGHT");
            insertSetting(conn, businessId, "pref.language", "en");
            insertSetting(conn, businessId, "pref.date_format", "YYYY-MM-DD");
            insertSetting(conn, businessId, "pref.number_format", "SOUTH_ASIAN");
            insertSetting(conn, businessId, "pref.default_low_stock", "10");
            insertSetting(conn, businessId, "pref.invoice_header", "Wholesale & Retail Groceries");
            insertSetting(conn, businessId, "pref.invoice_footer", "Thank you for shopping with Mizan Stores! Please visit again.");
            insertSetting(conn, businessId, "owner_recovery_hash", mizanCred.hashHex());
            insertSetting(conn, businessId, "owner_recovery_salt", mizanCred.saltHex());

            // 15. Table: audit_logs
            insertAuditLog(conn, businessId, userId, "ONBOARDING", "BUSINESS", businessId, "Store Mizan Stores initialized with default owner account");
            insertAuditLog(conn, businessId, userId, "CREATE", "PRODUCT", pOil, "Initial product catalog imported: Sunflower Oil, Basmati Rice, etc.");
            insertAuditLog(conn, businessId, userId, "CREATE", "USER", cashierUserId, "Provisioned cashier staff account @karim");
            insertAuditLog(conn, businessId, userId, "CREATE", "SALE", sale1, "Completed sale INV-2025-001 (BDT 25,340)");

            log.info("Reference data seeding completed successfully across all 19 SQLite tables.");
        });
    }

    public record SeedCatalogResult(int productsAdded, int customersAdded) {}

    /**
     * Additive, idempotent helper that seeds realistic grocery products (using SVG artwork <= 25 KB)
     * and Bangladeshi customers into an existing store without duplicating existing items or creating fake dues.
     */
    public static synchronized SeedCatalogResult seedRichGroceryCatalogAndCustomers(long businessId, long actingUserId) {
        return TransactionManager.executeInTransaction(conn -> {
            long catOil = getOrCreateCategory(conn, businessId, "Cooking Oil & Ghee", "Edible oils, mustard oil, soybean oil & pure ghee");
            long catRice = getOrCreateCategory(conn, businessId, "Rice & Grains", "Aromatic, miniket, nazirshail & basmati rice");
            long catSpices = getOrCreateCategory(conn, businessId, "Salt, Sugar & Spices", "Iodized salt, packaged sugar & essential spices");
            long catFlour = getOrCreateCategory(conn, businessId, "Flour & Bakery", "Atta, maida, suji & bakery ingredients");
            long catLentils = getOrCreateCategory(conn, businessId, "Lentils & Pulses", "Deshi masoor, mug dal, chhola & chickpeas");
            long catDairy = getOrCreateCategory(conn, businessId, "Dairy & Beverages", "Milk powder, black tea, condensed milk & drinks");
            long catHousehold = getOrCreateCategory(conn, businessId, "Household & Cleaning", "Dishwash, laundry detergent & home care");

            String pfx = "B" + businessId + "-";
            int productsAdded = 0;

            if (insertProductIfMissing(conn, businessId, catOil, "Rupchanda Soybean Oil (5 Ltr)", "8941100100011", pfx + "OIL-5L",
                    "pcs", "carton", 4, 800, 820, 920, 910, 10,
                    "/com/smartkhata/ui/images/products_svg/soyabean_oil.svg", 25, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catRice, "Miniket Premium Rice (25 kg)", "8941100100028", pfx + "RICE-25K",
                    "bag", "sack", 1, 1710, 1750, 1980, 1950, 8,
                    "/com/smartkhata/ui/images/products_svg/miniket_rice.svg", 40, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catSpices, "ACI Pure Iodized Salt (1 kg)", "8941100100035", pfx + "SALT-1K",
                    "pcs", "pack", 25, 36, 38, 46, 45, 15,
                    "/com/smartkhata/ui/images/products_svg/iodized_salt.svg", 35, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catSpices, "Fresh Refined Sugar (1 kg)", "8941100100042", pfx + "SUGAR-1K",
                    "pcs", "sack", 50, 120, 125, 148, 145, 12,
                    "/com/smartkhata/ui/images/products_svg/sugar_pack.svg", 45, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catFlour, "Teer Whole Wheat Atta (2 kg)", "8941100100059", pfx + "ATTA-2K",
                    "pcs", "bundle", 12, 110, 115, 138, 135, 10,
                    "/com/smartkhata/ui/images/products_svg/atta_flour.svg", 30, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catLentils, "Deshi Masoor Dal (1 kg)", "8941100100066", pfx + "DAL-1K",
                    "pcs", "sack", 25, 126, 130, 158, 155, 12,
                    "/com/smartkhata/ui/images/products_svg/masoor_dal.svg", 22, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catOil, "Radhuni Pure Mustard Oil (1 Ltr)", "8941100100073", pfx + "MOIL-1L",
                    "pcs", "carton", 12, 265, 270, 315, 310, 8,
                    "/com/smartkhata/ui/images/products_svg/mustard_oil.svg", 18, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catDairy, "Diploma Full Cream Milk Powder (500 g)", "8941100100080", pfx + "MILK-500G",
                    "pcs", "carton", 12, 390, 400, 460, 450, 6,
                    "/com/smartkhata/ui/images/products_svg/milk_powder.svg", 16, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catDairy, "Ispahani Mirzapore Tea (400 g)", "8941100100097", pfx + "TEA-400G",
                    "pcs", "carton", 20, 195, 200, 235, 230, 8,
                    "/com/smartkhata/ui/images/products_svg/tea_leaf.svg", 24, actingUserId)) productsAdded++;
            if (insertProductIfMissing(conn, businessId, catHousehold, "Vim Dishwash Liquid (500 ml)", "8941100100103", pfx + "DISH-500M",
                    "pcs", "carton", 24, 125, 130, 160, 155, 10,
                    "/com/smartkhata/ui/images/products_svg/dish_wash.svg", 28, actingUserId)) productsAdded++;

            int customersAdded = 0;
            if (insertCustomerIfMissing(conn, businessId, "Rahim Chowdhury", "01711223344",
                    "House 14, Road 6, Mirpur-10, Dhaka", Money.ofBdt(50000),
                    "Regular monthly household grocery buyer")) customersAdded++;
            if (insertCustomerIfMissing(conn, businessId, "Karim Enterprise", "01722334455",
                    "Shop 28, Karwan Bazar, Dhaka", Money.ofBdt(80000),
                    "Catering & office pantry wholesale account")) customersAdded++;
            if (insertCustomerIfMissing(conn, businessId, "Noman General Store", "01733445566",
                    "Bazar Road, Savar, Dhaka", Money.ofBdt(60000),
                    "Neighborhood retailer (weekly settlement)")) customersAdded++;
            if (insertCustomerIfMissing(conn, businessId, "Mina Grocery Corner", "01844556677",
                    "Sector 7, Uttara, Dhaka", Money.ofBdt(40000),
                    "Frequent cash + bKash customer")) customersAdded++;
            if (insertCustomerIfMissing(conn, businessId, "Sumon Mia", "01955667788",
                    "Dhanmondi 15, Dhaka", Money.ofBdt(20000),
                    "Evening retail customer")) customersAdded++;
            if (insertCustomerIfMissing(conn, businessId, "Salma Akter", "01666778899",
                    "Banani Block C, Dhaka", Money.ofBdt(30000),
                    "Monthly family rations buyer")) customersAdded++;

            if (productsAdded > 0 || customersAdded > 0) {
                insertAuditLog(conn, businessId, actingUserId, "SEED_CATALOG", "BUSINESS", businessId,
                        "Added " + productsAdded + " SVG catalog products and " + customersAdded + " customers");
            }
            return new SeedCatalogResult(productsAdded, customersAdded);
        });
    }

    /**
     * Seeds realistic current-date grocery demonstration data for any business on demand.
     * Idempotent: safely avoids duplicating data or colliding on UNIQUE constraints if run repeatedly.
     *
     * @return true if new demo dataset was seeded, false if demo dataset already existed.
     */
    public static synchronized boolean seedDemoDataForBusiness(long businessId, long ownerUserId) {
        return TransactionManager.executeInTransaction(conn -> {
            String purNo = "PUR-" + businessId + "-001";
            String inv1 = "INV-" + businessId + "-001";
            if (existsByQuery(conn, "SELECT 1 FROM sales WHERE invoice_number = ? LIMIT 1", inv1)
                    || existsByQuery(conn, "SELECT 1 FROM purchases WHERE purchase_number = ? LIMIT 1", purNo)) {
                log.info("Demo dataset already present for business [{}]; skipping duplicate seed.", businessId);
                return false;
            }

            log.info("Seeding realistic current-date demonstration data across all 19 tables for business [{}]...", businessId);
            LocalDateTime now = LocalDateTime.now();

            // 1. Ensure sample Cashier user exists
            ensureSampleCashierUser(conn, businessId);

            // 2. Categories (reusing existing if already created)
            long catOil = getOrCreateCategory(conn, businessId, "Cooking Oil & Ghee", "Edible oils, mustard oil, soybean oil & pure ghee");
            long catRice = getOrCreateCategory(conn, businessId, "Rice & Grains", "Aromatic, miniket, nazirshail & basmati rice");
            long catSpices = getOrCreateCategory(conn, businessId, "Salt, Sugar & Spices", "Iodized salt, packaged sugar & essential spices");
            long catFlour = getOrCreateCategory(conn, businessId, "Flour & Bakery", "Atta, maida, suji & bakery ingredients");
            long catLentils = getOrCreateCategory(conn, businessId, "Lentils & Pulses", "Deshi masoor, mug dal, chhola & chickpeas");
            long catDairy = getOrCreateCategory(conn, businessId, "Dairy & Beverages", "Milk powder, black tea, condensed milk & drinks");
            long catHousehold = getOrCreateCategory(conn, businessId, "Household & Cleaning", "Dishwash, laundry detergent & home care");

            // 3. Suppliers + matching opening payables in supplier_ledger
            long sMeghna = getOrCreateSupplierWithOpening(conn, businessId, "Meghna Wholesale Traders", "01811223344", "Badamtoli, Dhaka", "Al-Hajj Rafiqul Islam", 25000 * 100L, now.minusDays(15).format(ISO_DT));
            getOrCreateSupplierWithOpening(conn, businessId, "Square Consumer Distributors", "01799887766", "Mohakhali C/A, Dhaka", "Kabir Hossain", 15000 * 100L, now.minusDays(15).format(ISO_DT));
            getOrCreateSupplierWithOpening(conn, businessId, "Bashundhara Agro Supply", "01611002233", "Baridhara, Dhaka", "Tareq Aziz", 10000 * 100L, now.minusDays(15).format(ISO_DT));

            // 4. Customers + matching opening receivables in customer_ledger
            long cRahim = getOrCreateCustomerWithOpening(conn, businessId, "Rahim Chowdhury", "01711223344", Money.ofBdt(50000), 12500 * 100L, now.minusDays(10).format(ISO_DT));
            long cKarim = getOrCreateCustomerWithOpening(conn, businessId, "Karim Enterprise", "01722334455", Money.ofBdt(80000), 0L, now.minusDays(10).format(ISO_DT));
            getOrCreateCustomerWithOpening(conn, businessId, "Noman General Store", "01733445566", Money.ofBdt(60000), 8500 * 100L, now.minusDays(8).format(ISO_DT));

            // 5. Products with SVG artwork (<= 25 KB each) + full pricing/barcode/unit metadata + opening stock
            String pfx = "B" + businessId + "-";
            long pOil = getOrCreateProductWithImage(conn, businessId, catOil, "Rupchanda Soybean Oil (5 Ltr)", "pcs", 820, 910, 10, "/com/smartkhata/ui/images/products_svg/soyabean_oil.svg", pfx + "OIL-5L", 25, ownerUserId);
            long pRice = getOrCreateProductWithImage(conn, businessId, catRice, "Miniket Premium Rice (25 kg)", "bag", 1750, 1950, 8, "/com/smartkhata/ui/images/products_svg/miniket_rice.svg", pfx + "RICE-25K", 40, ownerUserId);
            getOrCreateProductWithImage(conn, businessId, catSpices, "Aci Pure Salt (1 kg)", "pcs", 38, 45, 15, "/com/smartkhata/ui/images/products_svg/iodized_salt.svg", pfx + "SALT-1K", 8, ownerUserId); // Triggers low stock alert!
            long pSugar = getOrCreateProductWithImage(conn, businessId, catSpices, "Fresh Refined Sugar (1 kg)", "pcs", 125, 145, 12, "/com/smartkhata/ui/images/products_svg/sugar_pack.svg", pfx + "SUGAR-1K", 45, ownerUserId);
            getOrCreateProductWithImage(conn, businessId, catFlour, "Teer Atta Pack (2 kg)", "pcs", 115, 135, 10, "/com/smartkhata/ui/images/products_svg/atta_flour.svg", pfx + "ATTA-2K", 30, ownerUserId);
            getOrCreateProductWithImage(conn, businessId, catLentils, "Deshi Masoor Dal (1 kg)", "pcs", 130, 155, 12, "/com/smartkhata/ui/images/products_svg/masoor_dal.svg", pfx + "DAL-1K", 22, ownerUserId);
            insertProductIfMissing(conn, businessId, catOil, "Radhuni Pure Mustard Oil (1 Ltr)", "8941100100073", pfx + "MOIL-1L", "pcs", "carton", 12, 265, 270, 315, 310, 8, "/com/smartkhata/ui/images/products_svg/mustard_oil.svg", 18, ownerUserId);
            insertProductIfMissing(conn, businessId, catDairy, "Diploma Full Cream Milk Powder (500 g)", "8941100100080", pfx + "MILK-500G", "pcs", "carton", 12, 390, 400, 460, 450, 6, "/com/smartkhata/ui/images/products_svg/milk_powder.svg", 16, ownerUserId);
            insertProductIfMissing(conn, businessId, catDairy, "Ispahani Mirzapore Tea (400 g)", "8941100100097", pfx + "TEA-400G", "pcs", "carton", 20, 195, 200, 235, 230, 8, "/com/smartkhata/ui/images/products_svg/tea_leaf.svg", 24, ownerUserId);
            insertProductIfMissing(conn, businessId, catHousehold, "Vim Dishwash Liquid (500 ml)", "8941100100103", pfx + "DISH-500M", "pcs", "carton", 24, 125, 130, 160, 155, 10, "/com/smartkhata/ui/images/products_svg/dish_wash.svg", 28, ownerUserId);

            // 6. Purchases & items + PURCHASE_IN stock movement
            long pur1 = insertPurchase(conn, businessId, purNo, sMeghna, 41000 * 100L, 25000 * 100L, 16000 * 100L, "PARTIAL", now.minusDays(5).format(ISO_DT), ownerUserId);
            insertPurchaseItem(conn, pur1, pOil, 50, 820 * 100L, 41000 * 100L);
            insertStockMovementWithRef(conn, businessId, pOil, "PURCHASE_IN", 50, 820 * 100L, "PURCHASE", pur1, "Inward purchase bill " + purNo, ownerUserId);
            insertSupplierLedgerEntry(conn, businessId, sMeghna, now.minusDays(5).format(ISO_DT), "PURCHASE_BILL", 0L, 41000 * 100L, 66000 * 100L);

            // 7. Sales & items + SALE_OUT stock movements
            long sale1 = insertSale(conn, businessId, inv1, cRahim, 3770 * 100L, 2000 * 100L, 1770 * 100L, "PARTIAL", now.minusDays(2).format(ISO_DT), ownerUserId);
            insertSaleItem(conn, sale1, pOil, 2, 910 * 100L, 820 * 100L, 0L, 1820 * 100L);
            insertSaleItem(conn, sale1, pRice, 1, 1950 * 100L, 1750 * 100L, 0L, 1950 * 100L);
            insertStockMovementWithRef(conn, businessId, pOil, "SALE_OUT", -2, 820 * 100L, "SALE", sale1, "Sale invoice " + inv1, ownerUserId);
            insertStockMovementWithRef(conn, businessId, pRice, "SALE_OUT", -1, 1750 * 100L, "SALE", sale1, "Sale invoice " + inv1, ownerUserId);
            insertCustomerLedgerEntry(conn, businessId, cRahim, now.minusDays(2).format(ISO_DT), "SALE_INVOICE", 3770 * 100L, 0L, 16270 * 100L);

            String inv2 = "INV-" + businessId + "-002";
            long sale2 = insertSale(conn, businessId, inv2, cKarim, 1450 * 100L, 1450 * 100L, 0L, "PAID", now.minusDays(1).format(ISO_DT), ownerUserId);
            insertSaleItem(conn, sale2, pSugar, 10, 145 * 100L, 125 * 100L, 0L, 1450 * 100L);
            insertStockMovementWithRef(conn, businessId, pSugar, "SALE_OUT", -10, 125 * 100L, "SALE", sale2, "Sale invoice " + inv2, ownerUserId);

            // 8. Payments & allocations
            String payNo1 = "PAY-" + businessId + "-001";
            long p1 = insertPayment(conn, businessId, payNo1, "CUSTOMER", cRahim, 2000 * 100L, now.minusDays(2).format(ISO_DT), "BKASH", ownerUserId);
            insertPaymentAllocation(conn, p1, sale1, null, 2000 * 100L);
            insertCustomerLedgerEntry(conn, businessId, cRahim, now.minusDays(2).format(ISO_DT), "PAYMENT_COLLECTION", 0L, 2000 * 100L, 14270 * 100L);

            String paySupNo = "PAY-SUP-" + businessId + "-001";
            long ps1 = insertPayment(conn, businessId, paySupNo, "SUPPLIER", sMeghna, 25000 * 100L, now.minusDays(5).format(ISO_DT), "BANK", ownerUserId);
            insertPaymentAllocation(conn, ps1, null, pur1, 25000 * 100L);
            insertSupplierLedgerEntry(conn, businessId, sMeghna, now.minusDays(5).format(ISO_DT), "PAYMENT_DISBURSEMENT", 25000 * 100L, 0L, 41000 * 100L);

            // 9. Expenses
            insertExpense(conn, businessId, "UTILITIES", 2400 * 100L, now.minusDays(3).format(ISO_DT), "DESCO Electricity Recharge", ownerUserId);
            insertExpense(conn, businessId, "TRANSPORT", 850 * 100L, now.minusDays(1).format(ISO_DT), "Van / Rickshaw Goods Transport", ownerUserId);

            // 10. Other Income
            insertOtherIncome(conn, businessId, "Carton & Tin Scrap Sale", 1200 * 100L, now.minusDays(2).format(ISO_DT), "CASH", "Empty carton boxes and oil tins sale", ownerUserId);

            // 11. Settings defaults (preserving existing preferences if already customized)
            insertSettingIfMissing(conn, businessId, "pref.theme", "LIGHT");
            insertSettingIfMissing(conn, businessId, "pref.date_format", "YYYY-MM-DD");
            insertSettingIfMissing(conn, businessId, "pref.number_format", "SOUTH_ASIAN");
            insertSettingIfMissing(conn, businessId, "pref.default_low_stock", "10");
            insertSettingIfMissing(conn, businessId, "pref.invoice_header", "Wholesale & Retail Groceries");
            insertSettingIfMissing(conn, businessId, "pref.invoice_footer", "Thank you for your business! Please visit again.");
            insertSetting(conn, businessId, "sample_demo_loaded", "true");

            // 12. Audit logs
            insertAuditLog(conn, businessId, ownerUserId, "SAMPLE_LOAD", "BUSINESS", businessId, "Sample grocery store catalogue and transactions loaded successfully");

            log.info("Demo data seeded successfully across all 19 tables for business [{}]", businessId);
            return true;
        });
    }

    private static boolean existsByQuery(Connection conn, String sql, String param) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void ensureSampleCashierUser(Connection conn, long bizId) throws Exception {
        String checkSql = "SELECT COUNT(*) FROM users WHERE business_id = ? AND role = 'CASHIER'";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setLong(1, bizId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }

        String candidateUsername = "cashier";
        if (existsByQuery(conn, "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE LIMIT 1", candidateUsername)) {
            candidateUsername = "cashier_" + bizId;
        }
        PasswordHasher.HashedCredential cred = PasswordHasher.hashPassword("1234".toCharArray());
        String sql = "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) VALUES (?, ?, ?, ?, ?, 'CASHIER')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, candidateUsername);
            ps.setString(3, "Karim Ullah (Cashier)");
            ps.setString(4, cred.hashHex());
            ps.setString(5, cred.saltHex());
            ps.executeUpdate();
        }
    }

    private static long getOrCreateCategory(Connection conn, long bizId, String name, String desc) throws Exception {
        String findSql = "SELECT id FROM categories WHERE business_id = ? AND name = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return insertCategory(conn, bizId, name, desc);
    }

    private static long getOrCreateSupplierWithOpening(Connection conn, long bizId, String name, String phone, String address, String contact, long openingPaisha, String openingDate) throws Exception {
        String findSql = "SELECT id FROM suppliers WHERE business_id = ? AND name = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        String sql = "INSERT INTO suppliers (business_id, name, phone, address, contact_person, opening_balance_paisha) VALUES (?, ?, ?, ?, ?, ?)";
        long id;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setString(4, address);
            ps.setString(5, contact);
            ps.setLong(6, openingPaisha);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        if (openingPaisha > 0) {
            insertSupplierLedgerEntry(conn, bizId, id, openingDate, "OPENING_BALANCE", 0L, openingPaisha, openingPaisha);
        }
        return id;
    }

    private static long getOrCreateCustomerWithOpening(Connection conn, long bizId, String name, String phone, Money limit, long openingPaisha, String openingDate) throws Exception {
        String findSql = "SELECT id FROM customers WHERE business_id = ? AND name = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        String sql = "INSERT INTO customers (business_id, name, phone, credit_limit_paisha, opening_balance_paisha) VALUES (?, ?, ?, ?, ?)";
        long id;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setLong(4, limit.paisha());
            ps.setLong(5, openingPaisha);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        if (openingPaisha > 0) {
            insertCustomerLedgerEntry(conn, bizId, id, openingDate, "OPENING_BALANCE", openingPaisha, 0L, openingPaisha);
        }
        return id;
    }

    private static boolean insertProductIfMissing(Connection conn, long bizId, Long catId, String name,
                                                  String barcode, String sku, String unit, String purchaseUnit, int packSize,
                                                  long tp, long cost, long mrp, long sell, int threshold,
                                                  String imagePath, int openingStock, long userId) throws Exception {
        String findSql = "SELECT id FROM products WHERE business_id = ? AND (name = ? COLLATE NOCASE OR sku = ? OR barcode = ?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, sku);
            ps.setString(4, barcode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return false;
            }
        }
        String sql = "INSERT INTO products (business_id, category_id, name, barcode, sku, unit, " +
                "cost_price_paisha, selling_price_paisha, low_stock_threshold, image_path, " +
                "trade_price_paisha, mrp_paisha, last_purchase_price_paisha, purchase_unit, pack_size) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        long id;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            if (catId != null) ps.setLong(2, catId); else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setString(3, name);
            ps.setString(4, barcode);
            ps.setString(5, sku);
            ps.setString(6, unit);
            ps.setLong(7, cost * 100L);
            ps.setLong(8, sell * 100L);
            ps.setInt(9, threshold);
            ps.setString(10, imagePath);
            ps.setLong(11, tp * 100L);
            ps.setLong(12, mrp * 100L);
            ps.setLong(13, tp * 100L);
            ps.setString(14, purchaseUnit);
            ps.setInt(15, Math.max(1, packSize));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        if (openingStock > 0) {
            insertStockMovement(conn, bizId, id, "OPENING", openingStock, cost * 100L, userId);
        }
        return true;
    }

    private static boolean insertCustomerIfMissing(Connection conn, long bizId, String name, String phone,
                                                   String address, Money creditLimit, String notes) throws Exception {
        String findSql = "SELECT id FROM customers WHERE business_id = ? AND (name = ? COLLATE NOCASE OR phone = ?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return false;
            }
        }
        String sql = "INSERT INTO customers (business_id, name, phone, address, credit_limit_paisha, opening_balance_paisha, notes) " +
                "VALUES (?, ?, ?, ?, ?, 0, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setString(4, address);
            ps.setLong(5, creditLimit.paisha());
            ps.setString(6, notes);
            ps.executeUpdate();
        }
        return true;
    }

    private static long getOrCreateProductWithImage(Connection conn, long bizId, Long catId, String name, String unit, long cost, long sell, int threshold, String imagePath, String sku, int openingStock, long userId) throws Exception {
        String findSql = "SELECT id FROM products WHERE business_id = ? AND name = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        long id = insertProductWithImage(conn, bizId, catId, name, unit, cost, sell, threshold, imagePath, sku);
        if (openingStock > 0) {
            insertStockMovement(conn, bizId, id, "OPENING", openingStock, cost * 100L, userId);
        }
        return id;
    }

    private static void insertStockMovementWithRef(Connection conn, long bizId, long prodId, String type, int qty, long costPaisha, String refType, long refId, String notes, long userId) throws Exception {
        String sql = "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, reference_id, notes, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setLong(2, prodId);
            ps.setString(3, type);
            ps.setInt(4, qty);
            ps.setLong(5, costPaisha);
            ps.setString(6, refType);
            ps.setLong(7, refId);
            ps.setString(8, notes);
            ps.setLong(9, userId);
            ps.executeUpdate();
        }
    }

    private static void insertSettingIfMissing(Connection conn, long bizId, String key, String val) throws Exception {
        String sql = "INSERT OR IGNORE INTO settings (business_id, setting_key, setting_value) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, key);
            ps.setString(3, val);
            ps.executeUpdate();
        }
    }

    private static long insertCategory(Connection conn, long bizId, String name, String desc) throws Exception {
        String sql = "INSERT INTO categories (business_id, name, description) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, desc);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertSupplier(Connection conn, long bizId, String name, String phone, String address, String contact) throws Exception {
        String sql = "INSERT INTO suppliers (business_id, name, phone, address, contact_person) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setString(4, address);
            ps.setString(5, contact);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertCustomer(Connection conn, long bizId, String name, String phone, Money limit) throws Exception {
        String sql = "INSERT INTO customers (business_id, name, phone, credit_limit_paisha) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setLong(4, limit.paisha());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long insertProductWithImage(Connection conn, long bizId, Long catId, String name, String unit, long cost, long sell, int threshold, String imagePath, String sku) throws Exception {
        String sql = "INSERT INTO products (business_id, category_id, name, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold, image_path) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            if (catId != null) ps.setLong(2, catId); else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setString(3, name);
            ps.setString(4, sku);
            ps.setString(5, unit);
            ps.setLong(6, cost * 100L);
            ps.setLong(7, sell * 100L);
            ps.setInt(8, threshold);
            ps.setString(9, imagePath);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertStockMovement(Connection conn, long bizId, long prodId, String type, int qty, long costPaisha, long userId) throws Exception {
        String sql = "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setLong(2, prodId);
            ps.setString(3, type);
            ps.setInt(4, qty);
            ps.setLong(5, costPaisha);
            ps.setLong(6, userId);
            ps.executeUpdate();
        }
    }

    private static long insertPurchase(Connection conn, long bizId, String purNo, long suppId, long total, long paid, long due, String status, String date, long userId) throws Exception {
        String sql = "INSERT INTO purchases (business_id, purchase_number, supplier_id, total_amount_paisha, discount_paisha, net_amount_paisha, paid_amount_paisha, due_amount_paisha, payment_status, purchase_date, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, purNo);
            ps.setLong(3, suppId);
            ps.setLong(4, total);
            ps.setLong(5, total);
            ps.setLong(6, paid);
            ps.setLong(7, due);
            ps.setString(8, status);
            ps.setString(9, date);
            ps.setLong(10, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertPurchaseItem(Connection conn, long purId, long prodId, int qty, long costPaisha, long lineTotalPaisha) throws Exception {
        String sql = "INSERT INTO purchase_items (purchase_id, product_id, quantity, unit_cost_paisha, line_total_paisha) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, purId);
            ps.setLong(2, prodId);
            ps.setInt(3, qty);
            ps.setLong(4, costPaisha);
            ps.setLong(5, lineTotalPaisha);
            ps.executeUpdate();
        }
    }

    private static long insertSale(Connection conn, long bizId, String invNo, Long custId, long net, long paid, long due, String status, String date, long userId) throws Exception {
        String sql = "INSERT INTO sales (business_id, invoice_number, customer_id, total_amount_paisha, net_amount_paisha, paid_amount_paisha, due_amount_paisha, payment_status, invoice_date, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, invNo);
            if (custId != null) ps.setLong(3, custId); else ps.setNull(3, java.sql.Types.INTEGER);
            ps.setLong(4, net);
            ps.setLong(5, net);
            ps.setLong(6, paid);
            ps.setLong(7, due);
            ps.setString(8, status);
            ps.setString(9, date);
            ps.setLong(10, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertSaleItem(Connection conn, long saleId, long prodId, int qty, long unitPricePaisha, long costPricePaisha, long discountPaisha, long lineTotalPaisha) throws Exception {
        String sql = "INSERT INTO sale_items (sale_id, product_id, quantity, unit_price_paisha, unit_cost_paisha, discount_paisha, line_total_paisha) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            ps.setLong(2, prodId);
            ps.setInt(3, qty);
            ps.setLong(4, unitPricePaisha);
            ps.setLong(5, costPricePaisha);
            ps.setLong(6, discountPaisha);
            ps.setLong(7, lineTotalPaisha);
            ps.executeUpdate();
        }
    }

    private static long insertPayment(Connection conn, long bizId, String payNo, String partyType, long partyId, long amt, String date, String method, long userId) throws Exception {
        String sql = "INSERT INTO payments (business_id, payment_number, party_type, party_id, amount_paisha, payment_date, payment_method, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bizId);
            ps.setString(2, payNo);
            ps.setString(3, partyType);
            ps.setLong(4, partyId);
            ps.setLong(5, amt);
            ps.setString(6, date);
            ps.setString(7, method);
            ps.setLong(8, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertPaymentAllocation(Connection conn, long paymentId, Long saleId, Long purchaseId, long amtPaisha) throws Exception {
        String sql = "INSERT INTO payment_allocations (payment_id, sale_id, purchase_id, allocated_amount_paisha) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paymentId);
            if (saleId != null) ps.setLong(2, saleId); else ps.setNull(2, java.sql.Types.INTEGER);
            if (purchaseId != null) ps.setLong(3, purchaseId); else ps.setNull(3, java.sql.Types.INTEGER);
            ps.setLong(4, amtPaisha);
            ps.executeUpdate();
        }
    }

    private static void insertExpense(Connection conn, long bizId, String cat, long amt, String date, String payee, long userId) throws Exception {
        String sql = "INSERT INTO expenses (business_id, category, amount_paisha, expense_date, payment_method, payee_name, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, 'CASH', ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, cat);
            ps.setLong(3, amt);
            ps.setString(4, date);
            ps.setString(5, payee);
            ps.setLong(6, userId);
            ps.executeUpdate();
        }
    }

    private static void insertOtherIncome(Connection conn, long bizId, String source, long amt, String date, String method, String notes, long userId) throws Exception {
        String sql = "INSERT INTO other_incomes (business_id, source_name, amount_paisha, income_date, payment_method, notes, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, source);
            ps.setLong(3, amt);
            ps.setString(4, date);
            ps.setString(5, method);
            ps.setString(6, notes);
            ps.setLong(7, userId);
            ps.executeUpdate();
        }
    }

    private static void insertCustomerLedgerEntry(Connection conn, long bizId, long custId, String date, String type, long debit, long credit, long balance) throws Exception {
        String sql = "INSERT INTO customer_ledger (business_id, customer_id, entry_date, entry_type, debit_paisha, credit_paisha, running_balance_paisha) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setLong(2, custId);
            ps.setString(3, date);
            ps.setString(4, type);
            ps.setLong(5, debit);
            ps.setLong(6, credit);
            ps.setLong(7, balance);
            ps.executeUpdate();
        }
    }

    private static void insertSupplierLedgerEntry(Connection conn, long bizId, long suppId, String date, String type, long debit, long credit, long balance) throws Exception {
        String sql = "INSERT INTO supplier_ledger (business_id, supplier_id, entry_date, entry_type, debit_paisha, credit_paisha, running_balance_paisha) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setLong(2, suppId);
            ps.setString(3, date);
            ps.setString(4, type);
            ps.setLong(5, debit);
            ps.setLong(6, credit);
            ps.setLong(7, balance);
            ps.executeUpdate();
        }
    }

    private static void insertSetting(Connection conn, long bizId, String key, String val) throws Exception {
        String sql = "INSERT INTO settings (business_id, setting_key, setting_value) VALUES (?, ?, ?) " +
                "ON CONFLICT(business_id, setting_key) DO UPDATE SET setting_value = excluded.setting_value";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, key);
            ps.setString(3, val);
            ps.executeUpdate();
        }
    }

    private static void insertAuditLog(Connection conn, long bizId, Long userId, String action, String entity, Long entityId, String summary) throws Exception {
        String sql = "INSERT INTO audit_logs (business_id, user_id, action_type, entity_name, entity_id, change_summary) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            if (userId != null) ps.setLong(2, userId); else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setString(3, action);
            ps.setString(4, entity);
            if (entityId != null) ps.setLong(5, entityId); else ps.setNull(5, java.sql.Types.INTEGER);
            ps.setString(6, summary);
            ps.executeUpdate();
        }
    }
}

