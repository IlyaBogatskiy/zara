package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.NotifyEvent;
import com.ibdev.bot.zara.notify.UserNotifier;
import com.ibdev.bot.zara.service.page.PageService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.Watch;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.AUTO_AVAILABLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.WATCH_IN_STOCK;
import static com.ibdev.bot.zara.util.Sizes.equalsSize;
import static java.lang.Boolean.TRUE;

/**
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MonitoringScheduler {

    private final SubscriptionService subscriptionService;
    private final PageService pageService;
    private final UserNotifier userNotifier;
    private final ZaraProperties properties;

    /**
     * Last <em>confirmed</em> size availability: productKey → (size → inStock).
     * Private monitor state; persisted onto subscription rows after every check
     * (recordCheck) and restored from there after a restart.
     * A missing key/size is treated as "was not in stock". This holds the debounced state,
     * not the raw last observation (see {@link #pendingFlips}).
     */
    private final Map<String, Map<String, Boolean>> lastKnown = new ConcurrentHashMap<>();

    /**
     * Debounce buffer: productKey → (size → a raw observation that disagrees with {@link #lastKnown}
     * and how many consecutive times it has been seen). A change is committed only once it has been
     * observed {@code zara.monitor.confirmations} times in a row — this filters CDN/edge-cache blips
     * and "low stock sold in seconds" flaps. In-memory only; a restart just re-accumulates.
     */
    private final Map<String, Map<String, Pending>> pendingFlips = new ConcurrentHashMap<>();

    /**
     * Last known price per product — the price-change baseline, persisted on the product row.
     */
    private final Map<String, PriceInfo> lastKnownPrice = new ConcurrentHashMap<>();

    private static final class Pending {
        private final boolean value;
        private int count;

        private Pending(final boolean value) {
            this.value = value;
            this.count = 1;
        }
    }

    /**
     * Resumes monitoring from the state we stopped at before the restart.
     */
    @Order(3)
    @EventListener(ApplicationReadyEvent.class)
    public void seedLastKnown() {
        final var persisted = this.subscriptionService.loadLastKnown();
        if (!persisted.isEmpty()) {
            this.lastKnown.putAll(persisted);
            log.info("Seeded last-known availability for {} product(s) from DB.", persisted.size());
        }

        final var prices = this.subscriptionService.loadLastKnownPrices();
        if (!prices.isEmpty()) {
            this.lastKnownPrice.putAll(prices);
            log.info("Seeded last-known price for {} product(s) from DB.", prices.size());
        }
    }

    @Scheduled(fixedDelayString = "${zara.monitor.period-ms:60000}")
    public void monitor() {
        final var productKeys = this.subscriptionService.activeProductKeys();
        this.lastKnown.keySet().retainAll(productKeys);
        this.lastKnownPrice.keySet().retainAll(productKeys);
        this.pendingFlips.keySet().retainAll(productKeys);
        if (productKeys.isEmpty()) {
            log.info("No subscriptions found, nothing to monitor.");
            return;
        }

        final var startedAt = System.currentTimeMillis();
        // Accumulate every chat's changes across all products, then send one report per chat —
        // a burst of simultaneous changes becomes one message instead of many (fewer dropped by
        // Telegram rate limits). Insertion order preserves product/event ordering within a report.
        final var reports = new LinkedHashMap<Long, List<NotifyEvent>>();
        for (final var productKey : productKeys) {
            try {
                monitorProduct(productKey, reports);
            } catch (final Exception e) {
                log.error("Monitoring failed for productKey {}: {}", productKey, e.getMessage(), e);
            }
        }

        for (final var entry : reports.entrySet()) {
            try {
                this.userNotifier.sendReport(entry.getKey(), entry.getValue());
            } catch (final Exception e) {
                log.error("Failed to send report to chat {}: {}", entry.getKey(), e.getMessage(), e);
            }
        }

        log.info("Monitoring tick finished: {} product(s), {} chat report(s) in {} ms.",
                productKeys.size(), reports.size(), System.currentTimeMillis() - startedAt);
    }

    /**
     * One scrape per product per tick — regardless of how many chats subscribed. Changes are
     * appended to {@code reports} (chatId → events) rather than sent immediately; the caller flushes
     * one consolidated report per chat after the whole tick.
     */
    private void monitorProduct(final String productKey, final Map<Long, List<NotifyEvent>> reports) {
        final var ref = this.subscriptionService.getProductRef(productKey);
        if (ref == null) {
            log.warn("No product ref for productKey {}, skipping.", productKey);
            return;
        }

        final var snapshot = this.pageService.checkProductSizesAvailability(ref.link());
        if (snapshot == null || snapshot.sizes() == null || snapshot.sizes().isEmpty()) {
            log.warn("Empty availability state for productKey {}.", productKey);
            return;
        }

        final var raw = snapshot.sizes();
        final var price = snapshot.price();
        final var previous = this.lastKnown.getOrDefault(productKey, Map.of());
        final var watches = this.subscriptionService.getActiveWatches(productKey);

        // Debounce: fold the raw observation into the confirmed state, requiring N consecutive
        // agreeing observations before a change commits. `appeared` = sizes that just flipped OOS→in.
        final var appeared = new LinkedHashSet<String>();
        final var current = debounce(productKey, previous, raw, appeared);

        // A just-committed restock can still be a stale-API lie (the product's real page shows
        // "unavailable"). Cross-check the watched sizes against the Selenium/DOM path; on a definitive
        // contradiction revert the flip so no false "appeared" goes out. On a Selenium error we trust
        // the API. Only watched sizes are confirmed — an unwatched size flip notifies no one.
        final var watchedSizes = new HashSet<String>();
        for (final var watch : watches) {
            watchedSizes.add(watch.size());
        }
        confirmRestockViaSelenium(ref.link(), appeared, current, watchedSizes);

        collectPriceChangeIfMoved(productKey, ref, price, watches, reports);

        for (final var watch : watches) {
            if (WHOLE.getSize().equals(watch.size())) {
                collectWholeProductIfAvailable(watch.chatId(), productKey, ref, current, reports);
            } else if (watch.mode() == WATCH_IN_STOCK) {
                collectSizeSoldOut(watch.chatId(), productKey, ref, watch.size(), previous, current, reports);
            } else {
                collectSizeIfAppeared(
                        watch.chatId(), productKey, ref, watch.size(), previous, current,
                        snapshot.lowStockSizes(), reports);
            }
        }

        this.lastKnown.put(productKey, current);
        this.subscriptionService.recordCheck(productKey, current);

        if (price != null) {
            this.lastKnownPrice.put(productKey, price);
            this.subscriptionService.recordPrice(productKey, price);
        }
    }

    /**
     * Applies the confirmation debounce: returns the new confirmed state, and fills {@code appeared}
     * with the sizes whose confirmed availability flips OOS→in-stock this tick. Comparison against the
     * previous confirmed state is fuzzy ("EU40" == "40") because a restart seeds it from subscribed
     * labels, which may differ from the scrape's raw labels.
     */
    private Map<String, Boolean> debounce(
            final String productKey,
            final Map<String, Boolean> previousConfirmed,
            final Map<String, Boolean> raw,
            final Set<String> appeared
    ) {
        final var need = Math.max(1, this.properties.getMonitor().getConfirmations());
        final var pending = this.pendingFlips.computeIfAbsent(productKey, k -> new HashMap<>());
        pending.keySet().retainAll(raw.keySet());

        final var confirmed = new LinkedHashMap<String, Boolean>(raw.size());
        for (final var entry : raw.entrySet()) {
            final var size = entry.getKey();
            final var rawValue = TRUE.equals(entry.getValue());
            final var confirmedValue = availability(previousConfirmed, size);

            if (rawValue == confirmedValue) {
                pending.remove(size);
                confirmed.put(size, confirmedValue);
                continue;
            }

            var flip = pending.get(size);
            if (flip == null || flip.value != rawValue) {
                flip = new Pending(rawValue);
                pending.put(size, flip);
            } else {
                flip.count++;
            }

            if (flip.count >= need) {
                pending.remove(size);
                confirmed.put(size, rawValue);
                if (rawValue) {
                    appeared.add(size);
                }
            } else {
                confirmed.put(size, confirmedValue);
            }
        }
        return confirmed;
    }

    /**
     * For every size that just flipped to in-stock, cross-check with the Selenium/DOM path. If it
     * definitively says the size/product is NOT available, revert the flip in {@code confirmed} and
     * drop it from {@code appeared} so no notification fires. Skipped when the API path is disabled
     * (the primary reading was already Selenium) or the config toggle is off. Runs at most one
     * Selenium scrape per product, and only on the rare tick when something restocked.
     */
    private void confirmRestockViaSelenium(
            final String link,
            final Set<String> appeared,
            final Map<String, Boolean> confirmed,
            final Set<String> watchedSizes
    ) {
        if (!this.properties.getMonitor().isConfirmRestockViaSelenium()
                || !this.properties.getApi().isEnabled()) {
            return;
        }

        final var toConfirm = new ArrayList<String>();
        for (final var size : appeared) {
            if (containsSize(watchedSizes, size)) {
                toConfirm.add(size);
            }
        }
        if (toConfirm.isEmpty()) {
            return;
        }

        final Map<String, Boolean> viaSelenium;
        try {
            final var seleniumSnapshot = this.pageService.checkViaSelenium(link);
            viaSelenium = (seleniumSnapshot == null) ? null : seleniumSnapshot.sizes();
        } catch (final Exception e) {
            log.warn("Selenium restock confirmation failed for {} ({}) — trusting the API result.",
                    link, e.getMessage());
            return;
        }
        if (viaSelenium == null) {
            return;
        }

        for (final var size : toConfirm) {
            if (!availability(viaSelenium, size)) {
                confirmed.put(size, false);
                appeared.remove(size);
                log.info("Restock of size '{}' on {} not confirmed by Selenium — suppressing notification.",
                        size, link);
            }
        }
    }

    private void add(final Map<Long, List<NotifyEvent>> reports, final long chatId, final NotifyEvent event) {
        reports.computeIfAbsent(chatId, id -> new ArrayList<>()).add(event);
    }

    /**
     * Collects a price-change event for every chat watching an in-stock size on this product.
     */
    private void collectPriceChangeIfMoved(
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final PriceInfo price,
            final List<Watch> watches,
            final Map<Long, List<NotifyEvent>> reports
    ) {
        if (price == null) {
            return;
        }

        final var previousPrice = this.lastKnownPrice.get(productKey);
        if (previousPrice == null || previousPrice.sameAmountAs(price)) {
            return;
        }

        final var chats = new HashSet<Long>();
        for (final var watch : watches) {
            if (watch.mode() == WATCH_IN_STOCK) {
                chats.add(watch.chatId());
            }
        }
        for (final var chatId : chats) {
            add(reports, chatId, new NotifyEvent.PriceMoved(
                    productKey, ref.name(), ref.link(), previousPrice, price));
        }
    }

    /**
     * AWAIT_RESTOCK: the out-of-stock size came back — offer to keep watching it.
     */
    private void collectSizeIfAppeared(
            final long chatId,
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final String size,
            final Map<String, Boolean> previous,
            final Map<String, Boolean> current,
            final Set<String> lowStockSizes,
            final Map<Long, List<NotifyEvent>> reports
    ) {
        if (!availability(previous, size) && availability(current, size)) {
            final var lowStock = containsSize(lowStockSizes, size);
            add(reports, chatId, new NotifyEvent.SizeAppeared(productKey, ref.name(), ref.link(), size, lowStock));
        }
    }

    /**
     * Fuzzy membership test ("EU40" matches a subscription for "40").
     */
    private boolean containsSize(final Set<String> sizes, final String size) {
        if (sizes == null) {
            return false;
        }
        for (final var candidate : sizes) {
            if (equalsSize(candidate, size)) {
                return true;
            }
        }
        return false;
    }

    /**
     * WATCH_IN_STOCK: the in-stock size sold out — offer to keep waiting for it.
     */
    private void collectSizeSoldOut(
            final long chatId,
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final String size,
            final Map<String, Boolean> previous,
            final Map<String, Boolean> current,
            final Map<Long, List<NotifyEvent>> reports
    ) {
        if (availability(previous, size) && !availability(current, size)) {
            add(reports, chatId, new NotifyEvent.SizeSoldOut(productKey, ref.name(), ref.link(), size));
        }
    }

    private void collectWholeProductIfAvailable(
            final long chatId,
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final Map<String, Boolean> current,
            final Map<Long, List<NotifyEvent>> reports
    ) {
        if (!TRUE.equals(current.get(WHOLE.getSize()))) {
            return;
        }

        final var availableSizes = new HashSet<String>();
        final var unavailableSizes = new HashSet<String>();
        for (final var entry : current.entrySet()) {
            if (WHOLE.getSize().equals(entry.getKey())) {
                continue;
            }
            if (TRUE.equals(entry.getValue())) {
                availableSizes.add(entry.getKey());
            } else {
                unavailableSizes.add(entry.getKey());
            }
        }

        add(reports, chatId, new NotifyEvent.WholeAvailable(
                productKey, ref.name(), ref.link(), availableSizes, unavailableSizes));

        this.subscriptionService.unsubscribe(chatId, productKey, Set.of(WHOLE.getSize()), AUTO_AVAILABLE);
    }

    /**
     * Looks a size up in the state map using fuzzy matching ("EU40" == "40").
     */
    private boolean availability(final Map<String, Boolean> state, final String size) {
        for (final var entry : state.entrySet()) {
            if (equalsSize(entry.getKey(), size)) {
                return TRUE.equals(entry.getValue());
            }
        }
        return false;
    }
}
