package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.client.ProductCard;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author i.bogatskii
 */
@Getter
@Setter
public class ChatSession {

    private final Set<String> selectedSizes = ConcurrentHashMap.newKeySet();

    private volatile ProductCard lastCard;
    private volatile boolean trackWholeProduct;

    /**
     * Set when the admin taps 🔎 Поиск — the next plain message is treated as the search query.
     */
    private volatile boolean awaitingAdminSearch;
}
