package com.ibdev.bot.zara.client;

import java.math.BigDecimal;

/**
 * @author i.bogatskii
 */
public record PriceInfo(long amount, String currency, int fractionDigits) {

    /**
     * The human-readable value.
     */
    public String formatted() {
        final var value = BigDecimal.valueOf(this.amount, Math.max(this.fractionDigits, 0)).toPlainString();
        return (this.currency == null || this.currency.isBlank()) ? value : value + " " + this.currency;
    }

    /**
     * Same money regardless of label/scrape source — compares the canonical minor amount.
     */
    public boolean sameAmountAs(final PriceInfo other) {
        return other != null && this.amount == other.amount;
    }
}
