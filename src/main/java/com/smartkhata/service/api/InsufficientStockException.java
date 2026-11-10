package com.smartkhata.service.api;

/**
 * Thrown when an inventory movement or sale is requested that exceeds currently available stock.
 * Enforces the invariant that physical stock cannot silently fall into negative values.
 */
public class InsufficientStockException extends RuntimeException {

    private final long productId;
    private final int availableStock;
    private final int requestedQuantity;

    public InsufficientStockException(long productId, int availableStock, int requestedQuantity) {
        super(String.format("Insufficient stock for product ID [%d]. Available: %d, Requested: %d",
                productId, availableStock, requestedQuantity));
        this.productId = productId;
        this.availableStock = availableStock;
        this.requestedQuantity = requestedQuantity;
    }

    public long getProductId() {
        return productId;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }
}

