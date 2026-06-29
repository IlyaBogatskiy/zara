package com.ibdev.bot.zara.storage.model;

/**
 * @author i.bogatskii
 */
public enum SubscriptionMode {

    /**
     * The size is out of stock: notify once it appears, then ask the user whether to keep watching.
     */
    AWAIT_RESTOCK,

    /**
     * The size is in stock and the user opted to keep watching: notify on price changes and if it sells out.
     */
    WATCH_IN_STOCK
}
