package com.ibdev.bot.zara.util;

import java.net.URI;

/**
 * Zara link helpers: productKey is the "-p<digits>" segment of the URL,
 * the primary correlation key across the whole system (cache, subscriptions, DB).
 *
 * @author i.bogatskii
 */
public final class ProductLinks {

    private ProductLinks() {
    }

    public static String extractProductId(final String link) {
        try {
            final var path = URI.create(link).getPath();
            final var idx = path.lastIndexOf("-p");
            if (idx < 0) {
                return path;
            }

            final var rest = path.substring(idx + 2);
            final var dot = rest.indexOf('.');
            return dot > 0 ? rest.substring(0, dot) : rest;
        } catch (final Exception e) {
            return link;
        }
    }
}
