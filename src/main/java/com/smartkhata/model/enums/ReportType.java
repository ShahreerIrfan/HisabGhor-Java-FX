package com.smartkhata.model.enums;

public enum ReportType {
    SALES_SUMMARY("Sales Summary (বিক্রয় সারসংক্ষেপ)"),
    PURCHASE_SUMMARY("Purchase Summary (ক্রয় সারসংক্ষেপ)"),
    BUSINESS_PROFIT_LOSS("Business Profit & Loss Statement (লাভ-ক্ষতি বিবরণী)"),
    PRODUCT_PROFITABILITY("Product Profitability & COGS (পণ্যভিত্তিক লাভ-ক্ষতি)"),
    CATEGORY_PROFITABILITY("Category Profitability (ক্যাটাগরিভিত্তিক লাভ-ক্ষতি)"),
    SUPPLIER_PURCHASE_ANALYSIS("Supplier Purchase Analysis (সরবরাহকারী ক্রয় বিশ্লেষণ)"),
    CUSTOMER_OUTSTANDING("Customer Receivables / Baki (গ্রাহক বাকি তালিকা)"),
    SUPPLIER_OUTSTANDING("Supplier Payables / Paona (পাওনা তালিকা)"),
    CUSTOMER_LEDGER("Customer Ledger Statement (গ্রাহক খতিয়ান বিবরণী)"),
    SUPPLIER_LEDGER("Supplier Ledger Statement (সরবরাহকারী খতিয়ান বিবরণী)"),
    PAYMENTS_SUMMARY("Payments & Collections (টাকা আদায় ও পরিশোধ বিবরণী)"),
    EXPENSE_SUMMARY("Operating Expenses (ব্যবসায়িক খরচ বিবরণী)"),
    OTHER_INCOME_SUMMARY("Other Income (অন্যান্য আয় বিবরণী)"),
    INVENTORY_VALUATION("Inventory Valuation & Low Stock (মজুদ ও স্বল্প মজুদ রিপোর্ট)");

    private final String displayName;

    ReportType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
