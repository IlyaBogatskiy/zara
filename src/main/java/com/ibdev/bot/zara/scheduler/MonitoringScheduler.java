package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.client.ProductSnapshot;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.AdminNotifier;
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
    private final AdminNotifier adminNotifier;

    /**
     * Wall-clock end of the previous completed tick, for the outage watchdog. 0 = no tick yet. A
     * resuming tick whose gap since this exceeds {@code zara.monitor.stall-alert-ms} reports the
     * outage window (during which stock changes were missed). Single-threaded scheduler, so this is
     * only ever read/written on the scheduling thread.
     */
    private long lastTickCompletedAt;

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

    /**
     * Anti-flap history: productKey → (size → its last committed flip and any active quarantine).
     * Drives burst-confirm eligibility (a recently-flipped size may not burst) and the flap quarantine
     * (a size that flipped back within the cooldown window has its restocks muted for a longer
     * stretch). In-memory only; a restart just re-accumulates. Only maintained when
     * {@code zara.monitor.anti-flap-cooldown-ticks} > 0.
     */
    private final Map<String, Map<String, FlipRecord>> flipHistory = new ConcurrentHashMap<>();

    /**
     * Monotonic tick counter (bumped once per {@link #monitor()} call) — the time base for the
     * anti-flap cooldown and quarantine, which reason in ticks (≈ periods) rather than wall-clock so
     * they stay deterministic and clock-free.
     */
    private long tick;

    private static final class Pending {
        private final boolean value;
        private int count;

        private Pending(final boolean value) {
            this.value = value;
            this.count = 1;
        }
    }

    /**
     * One size's anti-flap state: the last committed availability and the tick it committed on (for
     * the cooldown / flap-detection window), plus the tick until which the size is quarantined as a
     * known flapper (its restocks muted while {@code tick < quarantinedUntilTick}).
     */
    private static final class FlipRecord {
        private boolean lastValue;
        private long lastCommitTick;
        private long quarantinedUntilTick;
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
        this.flipHistory.keySet().retainAll(productKeys);
        if (productKeys.isEmpty()) {
            log.info("No subscriptions found, nothing to monitor.");
            return;
        }

        this.tick++;
        final var startedAt = System.currentTimeMillis();
        alertOnMonitoringGap(startedAt);
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

        final var finishedAt = System.currentTimeMillis();
        final var durationMs = finishedAt - startedAt;
        log.info("Monitoring tick finished: {} product(s), {} chat report(s) in {} ms.",
                productKeys.size(), reports.size(), durationMs);
        alertOnSlowTick(durationMs);
        this.lastTickCompletedAt = finishedAt;
    }

    /**
     * Outage watchdog: a resuming tick whose gap since the previous completed tick exceeds
     * {@code stall-alert-ms} reports the window during which monitoring was down and stock changes
     * were missed (host suspend, container pause). Detected on resume — a full freeze suspends every
     * thread, so there is nothing to detect it while it lasts. Skipped on the first tick after boot.
     */
    private void alertOnMonitoringGap(final long startedAt) {
        final var threshold = this.properties.getMonitor().getStallAlertMs();
        if (threshold <= 0 || this.lastTickCompletedAt == 0) {
            return;
        }
        final var gapMs = startedAt - this.lastTickCompletedAt;
        if (gapMs > threshold) {
            log.warn("MON monitoring gap: no tick completed for {} ms — stock changes in that window were missed.",
                    gapMs);
            this.adminNotifier.alert("monitoring-gap", String.format(
                    "⚠️ Мониторинг простаивал %d мин — изменения наличия за это окно пропущены.",
                    Math.round(gapMs / 60000.0)));
        }
    }

    /**
     * Alerts (throttled) when a tick ran longer than {@code slow-tick-alert-ms} — almost always a
     * synchronous Selenium fallback blocking the single-threaded scheduler, which stalls monitoring
     * of every product for the duration.
     */
    private void alertOnSlowTick(final long durationMs) {
        final var threshold = this.properties.getMonitor().getSlowTickAlertMs();
        if (threshold <= 0 || durationMs <= threshold) {
            return;
        }
        log.warn("MON slow tick: {} ms (threshold {} ms) — the scheduler was blocked, likely a Selenium fallback.",
                durationMs, threshold);
        this.adminNotifier.alert("slow-tick", String.format(
                "🐢 Тик мониторинга занял %d с — планировщик подвисал (вероятно, синхронный Selenium-fallback).",
                Math.round(durationMs / 1000.0)));
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

        foldObservation(productKey, previous, raw, current, appeared, false);
        burstConfirmIfNeeded(productKey, ref, watches, previous, current, appeared, burstBudget);

        final var watchedSizes = new HashSet<String>();
        for (final var watch : watches) {
            watchedSizes.add(watch.size());
        }
        confirmRestockViaSelenium(productKey, ref.link(), appeared, current, watchedSizes);
        suppressQuarantinedRestocks(productKey, appeared, current);

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

        recordFlips(productKey, previous, current);

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
     * agreeing observations; a disagreeing one resets it. Called once per tick with the scrape
     * ({@code burst == false}), and a second time by {@link #burstConfirmIfNeeded} with the re-scrape
     * ({@code burst == true}). Comparison against the previous confirmed state is fuzzy ("EU40" ==
     * "40") because a restart seeds it from subscribed labels.
     * <p>
     * When {@code burst} is set, a disagreement toward OOS is <em>not</em> advanced: burst-confirm
     * exists only to accelerate a restock (OOS→in-stock), so a sell-out must wait for the independent
     * cross-tick observation instead of being confirmed by a re-scrape seconds later. Folding a
     * seconds-apart OOS reading into the count once let a momentary blip commit a false sell-out
     * (immediately mirrored by an "appeared" on the next tick) — the notification spam this guards.
     */
    private void foldObservation(
            final String productKey,
            final Map<String, Boolean> previousConfirmed,
            final Map<String, Boolean> observation,
            final Map<String, Boolean> confirmed,
            final Set<String> appeared,
            final boolean burst
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

            if (burst && !rawValue) {
                confirmed.putIfAbsent(size, confirmedValue);
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
                log.info("MON [{}] size '{}' observed {} ({}/{}) — holding, confirmed stays {}",
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
        foldObservation(productKey, previous, rescrape.sizes(), confirmed, appeared, true);
    }

    /**
     * Whether a watched size has a pending <em>restock</em> (a not-yet-committed OOS→in-stock flip)
     * after the first observation — the only case where a burst re-scrape is worth it. A pending
     * sell-out is deliberately excluded: burst-confirm only accelerates restocks, so confirming a
     * sell-out is left to the independent cross-tick observation (see {@link #foldObservation}).
     */
    private boolean hasUnconfirmedWatchedChange(final String productKey, final List<Watch> watches) {
        final var pending = this.pendingFlips.get(productKey);
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        for (final var watch : watches) {
            for (final var entry : pending.entrySet()) {
                if (entry.getValue().value && equalsSize(entry.getKey(), watch.size())
                        && !burstSuppressed(productKey, entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Anti-flap: a size is barred from burst-confirm while it is inside the cooldown window after its
     * last committed flip, or while it is quarantined as a known flapper. In both cases its next
     * restock must earn the independent cross-tick confirmation rather than an immediate re-scrape
     * that would only echo the same stale CDN edge. Disabled (never suppresses) when the cooldown is 0.
     */
    private boolean burstSuppressed(final String productKey, final String size) {
        final var cooldown = this.properties.getMonitor().getAntiFlapCooldownTicks();
        if (cooldown <= 0) {
            return false;
        }
        final var record = flipRecord(productKey, size);
        if (record == null) {
            return false;
        }
        if (this.tick < record.quarantinedUntilTick) {
            return true;
        }
        return this.tick - record.lastCommitTick < cooldown;
    }

    /**
     * Mutes a restock for any just-appeared size that is currently quarantined as a flapper: reverts
     * the flip in {@code confirmed} back to OOS and drops it from {@code appeared}, so no false
     * "appeared" fires and — since the state stays OOS — no mirroring sold-out follows next tick. A
     * quarantined size only re-alerts once its quarantine has elapsed and it commits a fresh restock.
     * No-op when anti-flap is disabled or nothing appeared.
     */
    private void suppressQuarantinedRestocks(
            final String productKey, final Set<String> appeared, final Map<String, Boolean> confirmed) {
        if (this.properties.getMonitor().getAntiFlapCooldownTicks() <= 0 || appeared.isEmpty()) {
            return;
        }
        final var quarantined = new ArrayList<String>();
        for (final var size : appeared) {
            final var record = flipRecord(productKey, size);
            if (record != null && this.tick < record.quarantinedUntilTick) {
                quarantined.add(size);
            }
        }
        for (final var size : quarantined) {
            confirmed.put(size, false);
            appeared.remove(size);
            log.info("MON [{}] size '{}' restock SUPPRESSED — size quarantined as a flapper until tick {}.",
                    productKey, size, flipRecord(productKey, size).quarantinedUntilTick);
        }
    }

    /**
     * Records every availability flip committed this tick (the final, post-suppression previous →
     * current transition) into {@link #flipHistory}, and when a size flips back to its opposite state
     * within the cooldown window quarantines it as a flapper (so {@link #suppressQuarantinedRestocks}
     * mutes its later restocks). Running on the post-suppression state means a reverted flip leaves no
     * trace. No-op when anti-flap is disabled.
     */
    private void recordFlips(
            final String productKey, final Map<String, Boolean> previous, final Map<String, Boolean> current) {
        final var cooldown = this.properties.getMonitor().getAntiFlapCooldownTicks();
        if (cooldown <= 0) {
            return;
        }
        final var history = this.flipHistory.computeIfAbsent(productKey, k -> new HashMap<>());
        for (final var entry : current.entrySet()) {
            final var size = entry.getKey();
            if (WHOLE.getSize().equals(size)) {
                continue;
            }
            final var now = TRUE.equals(entry.getValue());
            if (now == availability(previous, size)) {
                continue;
            }
            final var record = history.computeIfAbsent(size, k -> new FlipRecord());
            final var hasPrior = record.lastCommitTick > 0;
            if (hasPrior && record.lastValue != now && this.tick - record.lastCommitTick <= cooldown) {
                record.quarantinedUntilTick = this.tick + this.properties.getMonitor().getFlapQuarantineTicks();
                log.info("MON [{}] size '{}' FLAP detected ({} → {} within {} tick(s)) — "
                                + "quarantining restocks until tick {}.",
                        productKey, size, avail(!now), avail(now),
                        this.tick - record.lastCommitTick, record.quarantinedUntilTick);
            }
            record.lastValue = now;
            record.lastCommitTick = this.tick;
        }
    }

    /**
     * Fuzzy lookup of a size's anti-flap record ("EU40" matches a record stored under "40").
     */
    private FlipRecord flipRecord(final String productKey, final String size) {
        final var history = this.flipHistory.get(productKey);
        if (history == null) {
            return null;
        }
        final var direct = history.get(size);
        if (direct != null) {
            return direct;
        }
        for (final var entry : history.entrySet()) {
            if (equalsSize(entry.getKey(), size)) {
                return entry.getValue();
            }
        }
        return null;
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
