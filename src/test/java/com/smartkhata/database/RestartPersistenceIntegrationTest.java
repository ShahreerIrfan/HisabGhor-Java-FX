package com.smartkhata.database;

import com.smartkhata.dao.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.vo.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RestartPersistenceIntegrationTest {

    private Path tempDbPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("persistence_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldPersistDataAcrossApplicationRestart() throws SQLException {
        BusinessDao businessDao = new SqliteBusinessDao();
        CustomerDao customerDao = new SqliteCustomerDao();
        ProductDao productDao = new SqliteProductDao();
        StockMovementDao stockDao = new SqliteStockMovementDao();

        long businessId;
        long customerId;
        long productId;

        // Session 1: Create and write records
        try (Connection conn = DatabaseManager.getConnection()) {
            Business business = businessDao.save(conn, new Business(
                    null, "Mizan Stores", "Mizan Enterprise Ltd.", "01711000000",
                    "Chawkbazar, Dhaka", "TRAD-12345", "BDT", null, null
            ));
            businessId = business.id();

            Customer customer = customerDao.save(conn, new Customer(
                    null, businessId, "Rahman Trading", "01811223344",
                    "Old Dhaka", Money.ofBdt(50000), Money.ofBdt(5000), false, "VIP", null, null
            ));
            customerId = customer.id();

            Product product = productDao.save(conn, new Product(
                    null, businessId, null, "Sunflower Oil (5 Ltr)", "89411001",
                    "SKU-OIL-5", "pcs", Money.ofBdt(850), Money.ofBdt(950), 5, false, null, null
            ));
            productId = product.id();

            // Record initial opening stock movement
            stockDao.save(conn, new StockMovement(
                    null, businessId, productId, "OPENING", 25,
                    Money.ofBdt(850), "MANUAL", null, "Initial inventory", null, null
            ));
        }

        // Simulate app shutdown: reset custom JDBC url or reopen connection
        // Session 2: Fresh connection verifying persisted records
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Business> loadedBusiness = businessDao.findById(conn, businessId);
            assertThat(loadedBusiness).isPresent();
            assertThat(loadedBusiness.get().name()).isEqualTo("Mizan Stores");

            Optional<Customer> loadedCustomer = customerDao.findById(conn, customerId);
            assertThat(loadedCustomer).isPresent();
            assertThat(loadedCustomer.get().name()).isEqualTo("Rahman Trading");
            assertThat(loadedCustomer.get().openingBalance().paisha()).isEqualTo(500000L); // BDT 5,000

            Optional<Product> loadedProduct = productDao.findById(conn, productId);
            assertThat(loadedProduct).isPresent();
            assertThat(loadedProduct.get().sellingPrice().paisha()).isEqualTo(95000L); // BDT 950

            int stock = productDao.getCurrentStock(conn, productId);
            assertThat(stock).isEqualTo(25);
        }
    }
}

