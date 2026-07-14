package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.client.ProductSnapshot;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * One monitoring tick: one scrape per product, changes accumulated per chat across all products,
     * then flushed as a single consolidated report per chat. Collapsing a burst of simultaneous
     * changes into one message keeps the count low, so far fewer notifications are dropped by
     * Telegram's rate limits. Insertion order preserves product/event ordering within a report.
     */
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
        final var reports = new LinkedHashMap<Long, List<NotifyEvent>>();
        final var burstBudget = new AtomicInteger(this.properties.getMonitor().getBurstConfirmMaxPerTick());
        for (final var productKey : productKeys) {
            try {
                monitorProduct(productKey, reports, burstBudget);
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
     * <p>
     * The raw observation is folded through {@link #foldObservation}, which requires N agreeing
     * observations before a change commits — {@code appeared} holds the sizes that just flipped
     * OOS→in-stock. When a watched size is still short of confirmation, {@link #burstConfirmIfNeeded}
     * fetches the missing observation immediately (a fast re-scrape) instead of waiting a whole
     * period. A just-committed restock may still be a stale-API lie (the real page shows
     * "unavailable"), so {@link #confirmRestockViaSelenium} optionally cross-checks the watched sizes
     * against the DOM and reverts an unconfirmed flip. The full per-tick state is logged at DEBUG for
     * post-mortem analysis of why an alert fired.
     * <p>
     * Every tracked size is notified on <em>both</em> availability transitions — appeared and sold
     * out — regardless of its {@link SubscriptionMode}. The mode only gates the extra <em>price</em>
     * alerts (WATCH_IN_STOCK), which the user opts into via the "keep watching" button.
     */
    private void monitorProduct(
            final String productKey,
            final Map<Long, List<NotifyEvent>> reports,
            final AtomicInteger burstBudget
    ) {
        final var ref = this.subscriptionService.getProductRef(productKey);
        if (ref == null) {
            log.warn("MON [{}] no product ref, skipping.", productKey);
            return;
        }

        final var snapshot = this.pageService.checkProductSizesAvailability(ref.link());
        if (snapshot == null || snapshot.sizes() == null || snapshot.sizes().isEmpty()) {
            log.warn("MON [{}] '{}' scrape returned no sizes — skipping tick (state unchanged, no alerts).",
                    productKey, ref.name());
            return;
        }

        final var raw = snapshot.sizes();
        final var price = snapshot.price();
        final var previous = this.lastKnown.getOrDefault(productKey, Map.of());
        final var watches = this.subscriptionService.getActiveWatches(productKey);

        log.debug("MON [{}] '{}' tick: raw={} price={} prevConfirmed={} watchers={}",
                productKey, ref.name(), raw, formatPrice(price), previous, watches.size());

        final var appeared = new LinkedHashSet<String>();
        final var current = new LinkedHashMap<String, Boolean>(raw.size());
        this.pendingFlips.computeIfAbsent(productKey, k -> new HashMap<>()).keySet().retainAll(raw.keySet());

        foldObservation(productKey, previous, raw, current, appeared);
        burstConfirmIfNeeded(productKey, ref, watches, previous, current, appeared, burstBudget);

        final var watchedSizes = new HashSet<String>();
        for (final var watch : watches) {
            watchedSizes.add(watch.size());
        }
        confirmRestockViaSelenium(productKey, ref.link(), appeared, current, watchedSizes);

        collectPriceChangeIfMoved(productKey, ref, price, watches, reports);

        for (final var watch : watches) {
            if (WHOLE.getSize().equals(watch.size())) {
                collectWholeProductIfAvailable(watch.chatId(), productKey, ref, current, reports);
            } else {
                collectSizeIfAppeared(
                        watch.chatId(), productKey, ref, watch.size(), previous, current,
                        snapshot.lowStockSizes(), reports);
                collectSizeSoldOut(watch.chatId(), productKey, ref, watch.size(), previous, current, reports);
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
     * Folds one observation into the running {@code confirmed}/{@code appeared} state, advancing the
     * per-size {@link #pendingFlips} counters: a change commits only after {@code confirmations}
     * agreeing observations; a disagreeing one resets it. Called once per tick with the scrape, and a
     * second time by {@link #burstConfirmIfNeeded} with the re-scrape. Comparison against the previous
     * confirmed state is fuzzy ("EU40" == "40") because a restart seeds it from subscribed labels.
     */
    private void foldObservation(
            final String productKey,
            final Map<String, Boolean> previousConfirmed,
            final Map<String, Boolean> observation,
            final Map<String, Boolean> confirmed,
            final Set<String> appeared
    ) {
        final var need = Math.max(1, this.properties.getMonitor().getConfirmations());
        final var pending = this.pendingFlips.computeIfAbsent(productKey, k -> new HashMap<>());

        for (final var entry : observation.entrySet()) {
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
                log.info("MON [{}] size '{}' CONFIRMED {} → {} after {}/{} consistent checks",
                        productKey, size, avail(confirmedValue), avail(rawValue), flip.count, need);
                if (rawValue) {
                    appeared.add(size);
                }
            } else {
                confirmed.put(size, confirmedValue);
                log.debug("MON [{}] size '{}' observed {} ({}/{}) — holding, confirmed stays {}",
                        productKey, size, avail(rawValue), flip.count, need, avail(confirmedValue));
            }
        }
    }

    /**
     * When a watched size is still short of confirmation after the first observation, fetch the
     * missing observation immediately — a fast API re-scrape after a short pause — instead of waiting
     * a whole {@code period-ms} for the next tick. This cuts the confirmation half of the notification
     * latency. Bounded by a per-tick budget so a "everything changed at once" tick cannot balloon; a
     * failed/empty re-scrape degrades gracefully to the normal cross-tick debounce (never worse).
     * Skipped when disabled or the API path is off (the primary reading was already Selenium).
     */
    private void burstConfirmIfNeeded(
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final List<Watch> watches,
            final Map<String, Boolean> previous,
            final Map<String, Boolean> confirmed,
            final Set<String> appeared,
            final AtomicInteger burstBudget
    ) {
        if (!this.properties.getMonitor().isBurstConfirm() || !this.properties.getApi().isEnabled()) {
            return;
        }
        if (!hasUnconfirmedWatchedChange(productKey, watches)) {
            return;
        }
        if (burstBudget.get() <= 0) {
            log.debug("MON [{}] burst-confirm skipped — per-tick budget exhausted, using cross-tick debounce.",
                    productKey);
            return;
        }
        burstBudget.decrementAndGet();

        if (!sleep(this.properties.getMonitor().getBurstConfirmDelayMs())) {
            return;
        }

        final ProductSnapshot rescrape;
        try {
            rescrape = this.pageService.checkProductSizesAvailability(ref.link());
        } catch (final Exception e) {
            log.warn("MON [{}] burst-confirm re-scrape failed ({}) — falling back to cross-tick debounce.",
                    productKey, e.getMessage());
            return;
        }
        if (rescrape == null || rescrape.sizes() == null || rescrape.sizes().isEmpty()) {
            log.debug("MON [{}] burst-confirm re-scrape returned nothing — falling back to cross-tick debounce.",
                    productKey);
            return;
        }

        log.info("MON [{}] burst-confirm: re-scraped to confirm a change without waiting a full period.",
                productKey);
        foldObservation(productKey, previous, rescrape.sizes(), confirmed, appeared);
    }

    /**
     * Whether a watched size is still pending (disagrees with the confirmed state but not yet
     * committed) after the first observation — the only case where a burst re-scrape is worth it.
     */
    private boolean hasUnconfirmedWatchedChange(final String productKey, final List<Watch> watches) {
        final var pending = this.pendingFlips.get(productKey);
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        for (final var watch : watches) {
            for (final var pendingSize : pending.keySet()) {
                if (equalsSize(pendingSize, watch.size())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sleep(final long ms) {
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * For every size that just flipped to in-stock, cross-check with the Selenium/DOM path. If it
     * definitively says the size/product is NOT available, revert the flip in {@code confirmed} and
     * drop it from {@code appeared} so no notification fires. Skipped when the API path is disabled
     * (the primary reading was already Selenium) or the config toggle is off. Runs at most one
     * Selenium scrape per product, and only on the rare tick when something restocked.
     */
    private void confirmRestockViaSelenium(
            final String productKey,
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

        log.info("MON [{}] restock of {} committed — cross-checking buyability via Selenium/DOM.",
                productKey, toConfirm);

        final Map<String, Boolean> viaSelenium;
        try {
            final var seleniumSnapshot = this.pageService.checkViaSelenium(link);
            viaSelenium = (seleniumSnapshot == null) ? null : seleniumSnapshot.sizes();
        } catch (final Exception e) {
            log.warn("MON [{}] Selenium restock confirmation FAILED ({}) — trusting the API result, alert will fire.",
                    productKey, e.getMessage());
            return;
        }
        if (viaSelenium == null) {
            log.warn("MON [{}] Selenium confirmation returned nothing — trusting the API result.", productKey);
            return;
        }

        for (final var size : toConfirm) {
            if (availability(viaSelenium, size)) {
                log.info("MON [{}] size '{}' restock CONFIRMED by Selenium — alert will fire.", productKey, size);
            } else {
                confirmed.put(size, false);
                appeared.remove(size);
                log.info("MON [{}] size '{}' restock REJECTED by Selenium (DOM says not buyable) — "
                        + "suppressing false 'appeared' alert.", productKey, size);
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
        if (previousPrice == null) {
            log.debug("MON [{}] price {} — no baseline yet, seeding (no alert).", productKey, formatPrice(price));
            return;
        }
        if (previousPrice.sameAmountAs(price)) {
            return;
        }

        final var chats = new HashSet<Long>();
        for (final var watch : watches) {
            if (watch.mode() == WATCH_IN_STOCK) {
                chats.add(watch.chatId());
            }
        }
        log.info("MON [{}] PRICE moved {} → {} — notifying {} price-watcher chat(s) {}",
                productKey, formatPrice(previousPrice), formatPrice(price), chats.size(), chats);
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
            log.info("MON [{}] → chat {}: ALERT SIZE_APPEARED '{}' (confirmed OOS→in-stock; lowStock={})",
                    productKey, chatId, size, lowStock);
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
            log.info("MON [{}] → chat {}: ALERT SIZE_SOLD_OUT '{}' (confirmed in-stock→OOS)",
                    productKey, chatId, size);
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

        log.info("MON [{}] → chat {}: ALERT WHOLE_AVAILABLE (in-stock={}, still-missing={}) — auto-unsubscribing '*'",
                productKey, chatId, availableSizes, unavailableSizes);
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

    private static String avail(final boolean inStock) {
        return inStock ? "in-stock" : "OOS";
    }

    private static String formatPrice(final PriceInfo price) {
        return price == null ? "n/a" : price.formatted();
    }
}
