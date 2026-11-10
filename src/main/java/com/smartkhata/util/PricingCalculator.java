package com.smartkhata.util;

import com.smartkhata.model.vo.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic financial calculator for Product Trade Price (TP), MRP,
 * Default Selling Price, Weighted Average Cost, Gross Profit, Margin %, and Markup %.
 */
public final class PricingCalculator {

    private PricingCalculator() {}

    /**
     * Profit Amount = Selling Price - Cost Price (can be negative if selling below cost).
     */
    public static Money calculateProfitAmount(Money sellingPrice, Money costPrice) {
        Money sell = sellingPrice != null ? sellingPrice : Money.ZERO;
        Money cost = costPrice != null ? costPrice : Money.ZERO;
        return sell.subtract(cost);
    }

    /**
     * Profit Margin (%) = (Gross Profit / Selling Price) * 100.
     * Returns 0.0 when selling price is zero.
     */
    public static double calculateProfitMarginPercent(Money sellingPrice, Money costPrice) {
        Money sell = sellingPrice != null ? sellingPrice : Money.ZERO;
        Money cost = costPrice != null ? costPrice : Money.ZERO;
        if (sell.paisha() <= 0L) {
            return 0.0;
        }
        long profitPaisha = sell.paisha() - cost.paisha();
        return BigDecimal.valueOf(profitPaisha)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(sell.paisha()), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Markup (%) = (Gross Profit / Cost) * 100.
     * Returns 0.0 when cost is zero.
     */
    public static double calculateMarkupPercent(Money sellingPrice, Money costPrice) {
        Money sell = sellingPrice != null ? sellingPrice : Money.ZERO;
        Money cost = costPrice != null ? costPrice : Money.ZERO;
        if (cost.paisha() <= 0L) {
            return 0.0;
        }
        long profitPaisha = sell.paisha() - cost.paisha();
        return BigDecimal.valueOf(profitPaisha)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(cost.paisha()), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Returns true when configured selling price is strictly lower than reference cost.
     */
    public static boolean isSellingBelowCost(Money sellingPrice, Money referenceCost) {
        if (sellingPrice == null || referenceCost == null) {
            return false;
        }
        return referenceCost.isPositive() && sellingPrice.paisha() < referenceCost.paisha();
    }

    /**
     * Returns true when configured selling price exceeds non-zero MRP.
     */
    public static boolean isSellingAboveMrp(Money sellingPrice, Money mrp) {
        if (sellingPrice == null || mrp == null) {
            return false;
        }
        return mrp.isPositive() && sellingPrice.paisha() > mrp.paisha();
    }

    /**
     * Computes Moving Weighted Average Cost in integer Paisha (HALF_UP).
     * Existing negative stock is floored to 0 before weighting with the new accepted receipt.
     */
    public static Money calculateWeightedAverageCost(int existingBaseQty, Money existingAvgCost,
                                                     int receivedBaseQty, Money receivedBaseUnitCost) {
        long safeExistingQty = Math.max(0L, existingBaseQty);
        long safeExistingCost = existingAvgCost != null ? Math.max(0L, existingAvgCost.paisha()) : 0L;
        long safeReceivedQty = Math.max(0L, receivedBaseQty);
        long safeReceivedCost = receivedBaseUnitCost != null ? Math.max(0L, receivedBaseUnitCost.paisha()) : 0L;

        long totalQty = safeExistingQty + safeReceivedQty;
        if (totalQty <= 0L) {
            return existingAvgCost != null ? existingAvgCost : Money.ZERO;
        }
        if (safeExistingQty == 0L) {
            return Money.ofPaisha(safeReceivedCost);
        }
        if (safeReceivedQty == 0L) {
            return Money.ofPaisha(safeExistingCost);
        }

        BigDecimal existingValue = BigDecimal.valueOf(safeExistingQty).multiply(BigDecimal.valueOf(safeExistingCost));
        BigDecimal incomingValue = BigDecimal.valueOf(safeReceivedQty).multiply(BigDecimal.valueOf(safeReceivedCost));
        long newAvgPaisha = existingValue.add(incomingValue)
                .divide(BigDecimal.valueOf(totalQty), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return Money.ofPaisha(newAvgPaisha);
    }

    /**
     * Computes effective base-unit cost from accepted purchase units, trade price, line discount,
     * allocated extra costs, and pack size conversion.
     */
    public static Money calculateEffectiveBaseUnitCost(int acceptedPurchaseQty, int packSize,
                                                       Money totalLineCostAfterExtras) {
        int safePack = Math.max(1, packSize);
        long totalBaseQty = (long) Math.max(0, acceptedPurchaseQty) * safePack;
        if (totalBaseQty <= 0L || totalLineCostAfterExtras == null) {
            return Money.ZERO;
        }
        long perBasePaisha = BigDecimal.valueOf(Math.max(0L, totalLineCostAfterExtras.paisha()))
                .divide(BigDecimal.valueOf(totalBaseQty), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return Money.ofPaisha(perBasePaisha);
    }
}

