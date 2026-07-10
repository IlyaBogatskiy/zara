package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.client.PriceInfo;

import java.util.Set;

/**
 * A single monitoring change destined for a chat. Accumulated per chat over a whole
 * monitoring tick and then rendered into one consolidated report ({@link UserNotifier#sendReport}),
 * instead of firing one Telegram message per change (fewer messages → fewer dropped by rate limits).
 *
 * @author i.bogatskii
 */
public sealed interface NotifyEvent {

    String productKey();

    String productName();

    String link();

    /**
     * An AWAIT_RESTOCK size came back in stock — offer "keep watching its price / stop".
     * {@code lowStock} = Zara reports it as low_on_stock (buyable, but likely to sell out fast).
     */
    record SizeAppeared(String productKey, String productName, String link, String size, boolean lowStock)
            implements NotifyEvent {
    }

    /**
     * A WATCH_IN_STOCK size sold out — offer "keep waiting for it / stop".
     */
    record SizeSoldOut(String productKey, String productName, String link, String size) implements NotifyEvent {
    }

    /**
     * The price moved while a chat was watching an in-stock size. Informational, no buttons.
     */
    record PriceMoved(String productKey, String productName, String link, PriceInfo oldPrice, PriceInfo newPrice)
            implements NotifyEvent {
    }

    /**
     * A whole-product ("*") subscription's product became available. Offers a "keep monitoring the
     * still-missing sizes" button when some sizes are still out of stock.
     */
    record WholeAvailable(
            String productKey,
            String productName,
            String link,
            Set<String> availableSizes,
            Set<String> unavailableSizes
    ) implements NotifyEvent {
    }
}
