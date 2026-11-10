package com.smartkhata.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provides transactional boundaries across multiple DAOs and services.
 * Ensures that sale creation, customer ledger posting, and stock movements
 * execute atomically inside a single ACID SQLite transaction.
 */
public final class TransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    @FunctionalInterface
    public interface TransactionAction<T> {
        T execute(Connection conn) throws Exception;
    }

    @FunctionalInterface
    public interface VoidTransactionAction {
        void execute(Connection conn) throws Exception;
    }

    private TransactionManager() {}

    /**
     * Executes the given action within an explicit atomic transaction with a return value.
     */
    public static <T> T executeInTransaction(TransactionAction<T> action) {
        try (Connection conn = DatabaseManager.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                T result = action.execute(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                    log.warn("Transaction rolled back due to error: {}", e.getMessage());
                } catch (SQLException rbEx) {
                    log.error("Failed to rollback transaction: {}", rbEx.getMessage(), rbEx);
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new DatabaseException("Transaction execution failed: " + e.getMessage(), e);
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to acquire connection for transaction: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the given void action within an explicit atomic transaction.
     */
    public static void runInTransaction(VoidTransactionAction action) {
        executeInTransaction(conn -> {
            action.execute(conn);
            return null;
        });
    }
}
