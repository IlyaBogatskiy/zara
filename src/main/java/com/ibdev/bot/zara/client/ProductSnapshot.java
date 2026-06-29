package com.ibdev.bot.zara.client;

import java.util.Map;

/**
 * @author i.bogatskii
 */
public record ProductSnapshot(Map<String, Boolean> sizes, PriceInfo price) {
}
