package com.ibdev.bot.zara.service.subscription;

import com.ibdev.bot.zara.storage.model.SubscriptionChangeReason;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.storage.model.Product;
import com.ibdev.bot.zara.storage.model.Subscription;
import com.ibdev.bot.zara.storage.repo.ProductRepository;
import com.ibdev.bot.zara.storage.repo.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.ibdev.bot.zara.util.Sizes.equalsSize;

/**
 * The subscriptions domain state. The source of truth is Postgres (synchronous
 * writes, always "DB first, then indexes"); the in-memory maps are mere read
 * indexes. This is deliberately NOT a cache: losing an entry would mean
 * silently stopping the monitoring.
 *
 * @author i.bogatskii
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    /**
     * Product link and name independent of the evictable card cache:
     * the scheduler needs them even after the card has expired.
     */
    public record ProductRef(String link, String name) {
    }

    private final ProductRepository productRepository;
    private final SubscriptionRepository subscriptionRepository;

    private final Map<Long, Map<String, Set<String>>> byChat = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> byProduct = new ConcurrentHashMap<>();
    private final Map<String, ProductRef> productRefs = new ConcurrentHashMap<>();

    /** Rebuilds the indexes from the DB at startup (after the legacy data migration). */
    @Order(2)
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        final var active = this.subscriptionRepository.findByClosedAtIsNull();
        if (active.isEmpty()) {
            log.info("No active subscriptions in DB, nothing to warm up.");
            return;
        }

        final var products = new HashMap<String, Product>();
        this.productRepository.findAllById(
                active.stream().map(Subscription::getProductKey).distinct().toList()
        ).forEach(p -> products.put(p.getProductKey(), p));

        for (final var subscription : active) {
            final var product = products.get(subscription.getProductKey());
            if (product == null) {
                log.warn("Subscription {} references missing product {}, skipping.",
                        subscription.getId(), subscription.getProductKey());
                continue;
            }

            index(
                    subscription.getChatId(),
                    subscription.getProductKey(),
                    product.getLink(),
                    product.getName(),
                    Set.of(subscription.getSizeLabel())
            );
        }

        log.info("Warmup complete: {} active subscription(s) restored.", active.size());
    }

    @Transactional
    public void subscribe(final long chatId, final ProductCard card, final Set<String> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return;
        }
        final var productKey = card.getProductKey();

        upsertProduct(productKey, card.getName(), card.getLink());
        for (final var size : sizes) {
            final var existing = this.subscriptionRepository
                    .findByChatIdAndProductKeyAndSizeLabel(chatId, productKey, size);

            if (existing == null) {
                this.subscriptionRepository.save(new Subscription(chatId, productKey, size));
            } else if (!existing.isActive()) {
                existing.reopen();
                this.subscriptionRepository.save(existing);
            }
        }

        index(chatId, productKey, card.getLink(), card.getName(), sizes);
        log.info("Chat {} subscribed to {} sizes {}", chatId, productKey, sizes);
    }

    @Transactional
    public void unsubscribe(
            final long chatId,
            final String productKey,
            final Set<String> sizes,
            final SubscriptionChangeReason reason
    ) {
        for (final var size : sizes) {
            final var subscription = this.subscriptionRepository
                    .findByChatIdAndProductKeyAndSizeLabel(chatId, productKey, size);

            if (subscription != null && subscription.isActive()) {
                subscription.close(reason);
                this.subscriptionRepository.save(subscription);
            }
        }

        final var perChat = this.byChat.get(chatId);
        if (perChat == null) {
            return;
        }
        final var subscribed = perChat.get(productKey);
        if (subscribed == null) {
            return;
        }

        subscribed.removeAll(sizes);
        if (subscribed.isEmpty()) {
            perChat.remove(productKey);
            dropChatFromProduct(chatId, productKey);
        }
        log.info("Chat {} unsubscribed from {} sizes {} ({})", chatId, productKey, sizes, reason);
    }

    @Transactional
    public void unsubscribeAll(final long chatId, final String productKey, final SubscriptionChangeReason reason) {
        final var active = this.subscriptionRepository
                .findByChatIdAndProductKeyAndClosedAtIsNull(chatId, productKey);
        active.forEach(subscription -> subscription.close(reason));
        this.subscriptionRepository.saveAll(active);

        final var perChat = this.byChat.get(chatId);
        if (perChat != null) {
            perChat.remove(productKey);
        }
        dropChatFromProduct(chatId, productKey);
        log.info("Chat {} unsubscribed from {} entirely ({})", chatId, productKey, reason);
    }

    /**
     * Records a scheduler check result: the last known size availability is stored
     * on the subscription rows — a restart resumes monitoring from the same point,
     * with no spurious duplicate notifications.
     */
    @Transactional
    public void recordCheck(final String productKey, final Map<String, Boolean> current) {
        final var active = this.subscriptionRepository.findByProductKeyAndClosedAtIsNull(productKey);
        if (active.isEmpty()) {
            return;
        }

        final var now = Instant.now();
        for (final var subscription : active) {
            final var available = lookup(current, subscription.getSizeLabel());
            if (available != null) {
                subscription.setLastKnownInStock(available);
                subscription.setLastCheckedAt(now);
            }
        }
        this.subscriptionRepository.saveAll(active);
    }

    /** The last persisted size availability — seeds the scheduler after a restart. */
    public Map<String, Map<String, Boolean>> loadLastKnown() {
        final var result = new HashMap<String, Map<String, Boolean>>();

        for (final var subscription : this.subscriptionRepository.findByClosedAtIsNull()) {
            if (subscription.getLastKnownInStock() == null) {
                continue;
            }
            result.computeIfAbsent(subscription.getProductKey(), k -> new HashMap<>())
                    .put(subscription.getSizeLabel(), subscription.getLastKnownInStock());
        }

        return result;
    }

    public Set<String> getSubscribedSizes(final long chatId, final String productKey) {
        final var perChat = this.byChat.get(chatId);
        if (perChat == null) {
            return Set.of();
        }
        final var sizes = perChat.get(productKey);
        return sizes == null ? Set.of() : Set.copyOf(sizes);
    }

    /** Snapshot of the chat's subscriptions: productKey → sizes. */
    public Map<String, Set<String>> getAllSubscribedSizes(final long chatId) {
        final var perChat = this.byChat.get(chatId);
        if (perChat == null) {
            return Map.of();
        }

        final var snapshot = new HashMap<String, Set<String>>();
        perChat.forEach((productKey, sizes) -> {
            if (!sizes.isEmpty()) {
                snapshot.put(productKey, Set.copyOf(sizes));
            }
        });
        return snapshot;
    }

    /** Every product with at least one subscription — the scheduler's unit of work. */
    public Set<String> activeProductKeys() {
        return Set.copyOf(this.byProduct.keySet());
    }

    public ProductRef getProductRef(final String productKey) {
        return this.productRefs.get(productKey);
    }

    /**
     * Like getProductRef, but with a fallback to the products table — keeps working
     * after the last subscription is gone and the index has been cleaned up.
     */
    public ProductRef findProductRef(final String productKey) {
        final var ref = this.productRefs.get(productKey);
        if (ref != null) {
            return ref;
        }

        return this.productRepository.findById(productKey)
                .map(product -> new ProductRef(product.getLink(), product.getName()))
                .orElse(null);
    }

    /** Snapshot of the product's subscribers: chatId → sizes. The reverse index that deduplicates scrapes. */
    public Map<Long, Set<String>> getSubscribersByProduct(final String productKey) {
        final var chats = this.byProduct.get(productKey);
        if (chats == null) {
            return Map.of();
        }

        final var snapshot = new HashMap<Long, Set<String>>();
        for (final var chatId : chats) {
            final var sizes = getSubscribedSizes(chatId, productKey);
            if (!sizes.isEmpty()) {
                snapshot.put(chatId, sizes);
            }
        }
        return snapshot;
    }

    private void upsertProduct(final String productKey, final String name, final String link) {
        final var product = this.productRepository.findById(productKey)
                .orElseGet(() -> new Product(productKey, name, link));

        product.setName(name);
        product.setLink(link);
        product.setLastScrapedAt(Instant.now());
        this.productRepository.save(product);
    }

    private Boolean lookup(final Map<String, Boolean> state, final String sizeLabel) {
        for (final var entry : state.entrySet()) {
            if (equalsSize(entry.getKey(), sizeLabel)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void index(
            final long chatId,
            final String productKey,
            final String link,
            final String name,
            final Set<String> sizes
    ) {
        this.byChat.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(productKey, k -> ConcurrentHashMap.newKeySet())
                .addAll(sizes);
        this.byProduct.computeIfAbsent(productKey, k -> ConcurrentHashMap.newKeySet()).add(chatId);
        this.productRefs.put(productKey, new ProductRef(link, name));
    }

    private void dropChatFromProduct(final long chatId, final String productKey) {
        final var chats = this.byProduct.get(productKey);
        if (chats == null) {
            return;
        }

        chats.remove(chatId);
        if (chats.isEmpty()) {
            this.byProduct.remove(productKey);
            this.productRefs.remove(productKey);
        }
    }
}
