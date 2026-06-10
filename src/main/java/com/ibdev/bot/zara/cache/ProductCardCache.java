package com.ibdev.bot.zara.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.service.page.PageService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Product card cache keyed by productKey (one card per product, not per chat).
 * Caffeine provides TTL (cards expire and get re-read) and request coalescing:
 * concurrent requests for the same product result in exactly one scrape.
 *
 * @author i.bogatskii
 */
@Component
public class ProductCardCache {

    private final PageService pageService;
    private final Cache<String, ProductCard> cache;

    public ProductCardCache(final PageService pageService) {
        this.pageService = pageService;
        this.cache = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofHours(6))
                .recordStats()
                .build();
    }

    /** Returns the card from the cache or scrapes it by link (one scrape per key). */
    public ProductCard getOrLoad(final String productKey, final String link) {
        return this.cache.get(productKey, key -> this.pageService.loadProductCard(link));
    }

    public ProductCard getIfPresent(final String productKey) {
        return this.cache.getIfPresent(productKey);
    }

    public void put(final String productKey, final ProductCard card) {
        this.cache.put(productKey, card);
    }

    public void invalidate(final String productKey) {
        this.cache.invalidate(productKey);
    }

    /** Caffeine metrics snapshot for the operator API. */
    public Map<String, Object> statsSnapshot() {
        final var stats = this.cache.stats();
        return Map.of(
                "size", this.cache.estimatedSize(),
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "loadSuccessCount", stats.loadSuccessCount(),
                "loadFailureCount", stats.loadFailureCount(),
                "evictionCount", stats.evictionCount()
        );
    }
}
