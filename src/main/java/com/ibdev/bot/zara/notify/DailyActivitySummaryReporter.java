package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.ActivityStats.Counts;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;

/**
 * Once a day, sends the admin chat a "how the bot lived" summary: the current monitoring footprint
 * (products, chats, size subscriptions) plus the day's user-facing event counts drained from
 * {@link ActivityStats}. This is the operational-statistics counterpart to the WARN/ERROR digest —
 * "what happened", not "what broke" — and is always sent (it is the report, not an exception).
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class DailyActivitySummaryReporter {

    private final SubscriptionService subscriptionService;
    private final ActivityStats activityStats;
    private final AdminNotifier adminNotifier;
    private final ZaraProperties properties;

    @Scheduled(cron = "${zara.activity-summary.cron:0 0 9 * * *}", zone = "${zara.activity-summary.zone:}")
    public void flush() {
        if (!this.properties.getActivitySummary().isEnabled()) {
            return;
        }
        this.adminNotifier.notice(render(this.activityStats.drain()));
    }

    /**
     * Builds the summary text from the drained counts and a fresh read of the subscription indexes.
     * Pure apart from the {@link SubscriptionService} reads, so it is directly unit-testable.
     */
    public String render(final Counts counts) {
        final var products = this.subscriptionService.activeProductKeys();
        final var chats = new HashSet<Long>();
        var sizes = 0;
        var awaiting = 0;
        var watching = 0;
        for (final var key : products) {
            for (final var watch : this.subscriptionService.getActiveWatches(key)) {
                chats.add(watch.chatId());
                if (WHOLE.getSize().equals(watch.size())) {
                    continue;
                }
                sizes++;
                if (watch.mode() == AWAIT_RESTOCK) {
                    awaiting++;
                } else {
                    watching++;
                }
            }
        }

        final var body = new StringBuilder("📈 Сводка за сутки\n");
        body.append("Товаров в мониторинге: ").append(products.size()).append('\n');
        body.append("Уникальных чатов: ").append(chats.size()).append('\n');
        body.append("Подписок на размеры: ").append(sizes)
                .append(" (ждут рестока: ").append(awaiting)
                .append(", следят: ").append(watching).append(")\n");

        body.append("\nСобытий за сутки: ").append(counts.total()).append('\n');
        body.append("✅ Появлений: ").append(counts.appeared()).append('\n');
        body.append("⚠️ Пропаж: ").append(counts.soldOut()).append('\n');
        body.append("💰 Ценовых: ").append(counts.priceMoved()).append('\n');
        body.append("📦 Товар целиком: ").append(counts.wholeAvailable());
        return body.toString();
    }
}
