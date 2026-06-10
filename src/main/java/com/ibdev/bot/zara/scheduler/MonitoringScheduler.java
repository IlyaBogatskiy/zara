package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.notify.UserNotifier;
import com.ibdev.bot.zara.service.page.PageService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.AUTO_AVAILABLE;
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

    /**
     * Last known size availability: productKey → (size → inStock).
     * Private monitor state; persisted onto subscription rows after every check
     * (recordCheck) and restored from there after a restart.
     * A missing key/size is treated as "was not in stock".
     */
    private final Map<String, Map<String, Boolean>> lastKnown = new ConcurrentHashMap<>();

    /** Resumes monitoring from the state we stopped at before the restart. */
    @Order(3)
    @EventListener(ApplicationReadyEvent.class)
    public void seedLastKnown() {
        final var persisted = this.subscriptionService.loadLastKnown();
        if (!persisted.isEmpty()) {
            this.lastKnown.putAll(persisted);
            log.info("Seeded last-known availability for {} product(s) from DB.", persisted.size());
        }
    }

    @Scheduled(fixedDelayString = "${zara.monitor.period-ms:60000}")
    public void monitor() {
        final var productKeys = this.subscriptionService.activeProductKeys();
        this.lastKnown.keySet().retainAll(productKeys);
        if (productKeys.isEmpty()) {
            log.info("No subscriptions found, nothing to monitor.");
            return;
        }

        final var startedAt = System.currentTimeMillis();
        for (final var productKey : productKeys) {
            try {
                monitorProduct(productKey);
            } catch (final Exception e) {
                log.error("Monitoring failed for productKey {}: {}", productKey, e.getMessage(), e);
            }
        }

        log.info("Monitoring tick finished: {} product(s) in {} ms.",
                productKeys.size(), System.currentTimeMillis() - startedAt);
    }

    /** One scrape per product per tick — regardless of how many chats subscribed. */
    private void monitorProduct(final String productKey) {
        final var ref = this.subscriptionService.getProductRef(productKey);
        if (ref == null) {
            log.warn("No product ref for productKey {}, skipping.", productKey);
            return;
        }

        final var current = this.pageService.checkProductSizesAvailability(ref.link());
        if (current == null || current.isEmpty()) {
            log.warn("Empty availability state for productKey {}.", productKey);
            return;
        }

        final var previous = this.lastKnown.getOrDefault(productKey, Map.of());

        for (final var subscriber : this.subscriptionService.getSubscribersByProduct(productKey).entrySet()) {
            final var chatId = subscriber.getKey();

            for (final var size : subscriber.getValue()) {
                if (WHOLE.getSize().equals(size)) {
                    notifyWholeProductIfAvailable(chatId, productKey, ref, current);
                } else {
                    notifySizeIfAppeared(chatId, productKey, ref, size, previous, current);
                }
            }
        }

        this.lastKnown.put(productKey, current);
        this.subscriptionService.recordCheck(productKey, current);
    }

    private void notifySizeIfAppeared(
            final long chatId,
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final String size,
            final Map<String, Boolean> previous,
            final Map<String, Boolean> current
    ) {
        final var was = availability(previous, size);
        final var now = availability(current, size);

        if (!was && now) {
            this.userNotifier.sizeAppeared(chatId, size, ref.name());
            this.subscriptionService.unsubscribe(chatId, productKey, Set.of(size), AUTO_AVAILABLE);
        }
    }

    private void notifyWholeProductIfAvailable(
            final long chatId,
            final String productKey,
            final SubscriptionService.ProductRef ref,
            final Map<String, Boolean> current
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

        this.userNotifier.wholeProductAvailable(chatId, productKey, ref, availableSizes, unavailableSizes);

        this.subscriptionService.unsubscribe(chatId, productKey, Set.of(WHOLE.getSize()), AUTO_AVAILABLE);
    }

    /** Looks a size up in the state map using fuzzy matching ("EU40" == "40"). */
    private boolean availability(final Map<String, Boolean> state, final String size) {
        for (final var entry : state.entrySet()) {
            if (equalsSize(entry.getKey(), size)) {
                return TRUE.equals(entry.getValue());
            }
        }
        return false;
    }
}
