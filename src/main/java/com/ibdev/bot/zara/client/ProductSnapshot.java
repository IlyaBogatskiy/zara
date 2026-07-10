package com.ibdev.bot.zara.client;

import java.util.Map;
import java.util.Set;

/**
 * @author i.bogatskii
 */
public record ProductSnapshot(Map<String, Boolean> sizes, PriceInfo price, Set<String> lowStockSizes) {

    public ProductSnapshot {
        lowStockSizes = (lowStockSizes == null) ? Set.of() : lowStockSizes;
    }

    public ProductSnapshot(final Map<String, Boolean> sizes, final PriceInfo price) {
        this(sizes, price, Set.of());
    }
}
