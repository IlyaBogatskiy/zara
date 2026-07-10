package com.ibdev.bot.zara.service.subscription;

import com.ibdev.bot.zara.storage.model.SubscriptionChangeReason;
import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.storage.model.Product;
import com.ibdev.bot.zara.storage.model.Subscription;
import com.ibdev.bot.zara.storage.model.SubscriptionMode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;
import static com.ibdev.bot.zara.util.Sizes.equalsSize;

/**
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

    /**
     * One active size watch — what the monitoring tick iterates over.
     */
    public record Watch(long chatId, String size, SubscriptionMode mode) {
    }

    private final ProductRepository productRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * chatId → productKey → (size → mode). The size's mode lives in the hot path so the scheduler need not hit the DB.
     */
    private final Map<Long, Map<String, Map<String, SubscriptionMode>>> byChat = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> byProduct = new ConcurrentHashMap<>();
    private final Map<String, ProductRef> productRefs = new ConcurrentHashMap<>();

    /**
     * Rebuilds the indexes from the DB at startup (after the legacy data migration).
     */
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

            final var mode = subscription.getMode() == null ? AWAIT_RESTOCK : subscription.getMode();
            index(
                    subscription.getChatId(),
                    subscription.getProductKey(),
                    product.getLink(),
                    product.getName(),
                    Map.of(subscription.getSizeLabel(), mode)
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

        final var sizeModes = new HashMap<String, SubscriptionMode>();
        for (final var size : sizes) {
            final var inStock = isSizeInStock(card, size);
            final var mode = inStock ? SubscriptionMode.WATCH_IN_STOCK : AWAIT_RESTOCK;

            var subscription = this.subscriptionRepository
                    .findByChatIdAndProductKeyAndSizeLabel(chatId, productKey, size);
            if (subscription == null) {
                subscription = new Subscription(chatId, productKey, size);
            } else if (!subscription.isActive()) {
                subscription.reopen();
            }
            subscription.setMode(mode);
            subscription.setLastKnownInStock(inStock);
            subscription.setLastCheckedAt(Instant.now());
            this.subscriptionRepository.save(subscription);

            sizeModes.put(size, mode);
        }

        index(chatId, productKey, card.getLink(), card.getName(), sizeModes);
        log.info("Chat {} subscribed to {} sizes {}", chatId, productKey, sizeModes);
    }

    /**
     * Fuzzy availability lookup of a size in the card's full lineup ("EU40" matches "40").
     * Unknown sizes (e.g. the WHOLE "*" sentinel) are treated as out of stock.
     */
    private boolean isSizeInStock(final ProductCard card, final String size) {
        if (card.getSizeDetails() == null) {
            return false;
        }
        for (final var sizeInfo : card.getSizeDetails()) {
            if (equalsSize(sizeInfo.getSize(), size)) {
                return sizeInfo.isSizeAvailability();
            }
        }
        return false;
    }

    /**
     * The user confirmed "keep watching" after the size came back: switch the row to
     * WATCH_IN_STOCK so the scheduler tracks its price and sells-out instead of restocks.
     * Returns false if the subscription no longer exists (the link must be re-sent).
     */
    @Transactional
    public boolean watchInStock(final long chatId, final String productKey, final String size) {
        return setMode(chatId, productKey, size, SubscriptionMode.WATCH_IN_STOCK, true);
    }

    /**
     * The user confirmed "keep waiting" after the size sold out: switch the row back to
     * AWAIT_RESTOCK so the scheduler notifies again once it reappears.
     */
    @Transactional
    public boolean awaitRestock(final long chatId, final String productKey, final String size) {
        return setMode(chatId, productKey, size, AWAIT_RESTOCK, false);
    }

    private boolean setMode(
            final long chatId,
            final String productKey,
            final String size,
            final SubscriptionMode mode,
            final boolean inStock
    ) {
        final var subscription = this.subscriptionRepository
                .findByChatIdAndProductKeyAndSizeLabel(chatId, productKey, size);
        if (subscription == null) {
            return false;
        }

        if (!subscription.isActive()) {
            subscription.reopen();
        }
        subscription.setMode(mode);
        subscription.setLastKnownInStock(inStock);
        subscription.setLastCheckedAt(Instant.now());
        this.subscriptionRepository.save(subscription);

        final var ref = findProductRef(productKey);
        if (ref != null) {
            index(chatId, productKey, ref.link(), ref.name(), Map.of(size, mode));
        }
        log.info("Chat {} set {} size {} to mode {}", chatId, productKey, size, mode);
        return true;
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

        sizes.forEach(subscribed::remove);
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

    /**
     * The last persisted size availability — seeds the scheduler after a restart.
     */
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
        return sizes == null ? Set.of() : Set.copyOf(sizes.keySet());
    }

    /**
     * The chat's tracked sizes for a product with their mode — lets the UI mark in-stock price watches.
     */
    public Map<String, SubscriptionMode> getSubscribedSizeModes(final long chatId, final String productKey) {
        final var perChat = this.byChat.get(chatId);
        if (perChat == null) {
            return Map.of();
        }
        final var sizeModes = perChat.get(productKey);
        return sizeModes == null ? Map.of() : Map.copyOf(sizeModes);
    }

    /**
     * Snapshot of the chat's subscriptions: productKey → sizes.
     */
    public Map<String, Set<String>> getAllSubscribedSizes(final long chatId) {
        final var perChat = this.byChat.get(chatId);
        if (perChat == null) {
            return Map.of();
        }

        final var snapshot = new HashMap<String, Set<String>>();
        perChat.forEach((productKey, sizes) -> {
            if (!sizes.isEmpty()) {
                snapshot.put(productKey, Set.copyOf(sizes.keySet()));
            }
        });
        return snapshot;
    }

    /**
     * Every product with at least one subscription — the scheduler's unit of work.
     */
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

    /**
     * Snapshot of the product's subscribers: chatId → sizes. The reverse index that deduplicates scrapes.
     */
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

    /**
     * Flat list of every active (chatId, size, mode) watch on the product — the scheduler's unit of work.
     */
    public List<Watch> getActiveWatches(final String productKey) {
        final var chats = this.byProduct.get(productKey);
        if (chats == null) {
            return List.of();
        }

        final var watches = new ArrayList<Watch>();
        for (final var chatId : chats) {
            final var perChat = this.byChat.get(chatId);
            if (perChat == null) {
                continue;
            }
            final var sizeModes = perChat.get(productKey);
            if (sizeModes == null) {
                continue;
            }
            sizeModes.forEach((size, mode) -> watches.add(new Watch(chatId, size, mode)));
        }
        return watches;
    }

    /**
     * Persists the last scraped price on the product row — the monitor's restart-safe price baseline.
     */
    @Transactional
    public void recordPrice(final String productKey, final PriceInfo price) {
        if (price == null) {
            return;
        }
        this.productRepository.findById(productKey).ifPresent(product -> {
            product.setLastPriceAmount(price.amount());
            product.setLastPriceCurrency(price.currency());
            product.setLastPriceFractionDigits(price.fractionDigits());
            this.productRepository.save(product);
        });
    }

    /**
     * The last persisted price per actively-monitored product — seeds the scheduler after a restart.
     */
    public Map<String, PriceInfo> loadLastKnownPrices() {
        final var keys = this.subscriptionRepository.findByClosedAtIsNull().stream()
                .map(Subscription::getProductKey).distinct().toList();

        final var result = new HashMap<String, PriceInfo>();
        this.productRepository.findAllById(keys).forEach(product -> {
            if (product.getLastPriceAmount() != null && product.getLastPriceFractionDigits() != null) {
                result.put(product.getProductKey(), new PriceInfo(
                        product.getLastPriceAmount(),
                        product.getLastPriceCurrency(),
                        product.getLastPriceFractionDigits()
                ));
            }
        });
        return result;
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
            final Map<String, SubscriptionMode> sizeModes
    ) {
        this.byChat.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(productKey, k -> new ConcurrentHashMap<>())
                .putAll(sizeModes);
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
