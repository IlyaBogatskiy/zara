package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.client.ProductCard;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author i.bogatskii
 */
@Getter
@Setter
public class ChatSession {

    private final Set<String> selectedSizes = ConcurrentHashMap.newKeySet();

    private final Map<String, String> subsTokens = new ConcurrentHashMap<>();

    private volatile String currentSubProductKey;

    private volatile ProductCard lastCard;
    private volatile boolean trackWholeProduct;
}
