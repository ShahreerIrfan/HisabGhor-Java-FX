package com.smartkhata.database;

/**
 * Unchecked exception thrown when a persistence, SQL constraint,
 * locking, or migration error occurs in SmartKhata.
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}

