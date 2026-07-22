package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.RecentEvents;
import com.ibdev.bot.zara.scheduler.MonitoringScheduler;
import com.ibdev.bot.zara.service.page.ApiHealthTracker;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Assembles the admin 🩺 status screen — a read-only ops snapshot pulling the otherwise-scattered
 * telemetry into one view: last tick timing, the API→Selenium fallback rate, flap quarantine, and the
 * relevant toggles. Rendering is pure ({@code now} injected) so it can be unit-tested deterministically.
 *
 * @author i.bogatskii
 */
@Component
@RequiredArgsConstructor
public class AdminStatusReporter {

    private final MonitoringScheduler scheduler;
    private final ApiHealthTracker apiHealthTracker;
    private final ZaraProperties properties;
    private final SubscriptionService subscriptionService;
    private final RecentEvents recentEvents;

    public String render() {
        return render(System.currentTimeMillis());
    }

    String render(final long now) {
        final var status = this.scheduler.status();
        final var api = this.apiHealthTracker.snapshot();
        final var monitor = this.properties.getMonitor();

        final var sb = new StringBuilder("🩺 Статус мониторинга\n");

        if (status.lastTickCompletedAt() == 0) {
            sb.append("Тик: ещё не выполнялся\n");
        } else {
            final var agoSec = Math.max(0, (now - status.lastTickCompletedAt()) / 1000);
            sb.append("Тик #").append(status.tick()).append(": ").append(agoSec)
                    .append(" сек назад, длился ").append(status.lastTickDurationMs()).append(" мс\n");
        }

        if (api.total() == 0) {
            sb.append("API-путь: нет данных");
        } else {
            sb.append(String.format("API-путь: fallback на Selenium %d/%d (%.0f%%)",
                    api.fallbacks(), api.total(), api.fallbackRate() * 100));
        }
        sb.append(api.breakerOpen() ? " — 🔌 предохранитель ОТКРЫТ (на Selenium)\n" : "\n");

        if (status.quarantinedSizes().isEmpty()) {
            sb.append("Карантин флапа: нет\n");
        } else {
            sb.append("Карантин флапа: ").append(status.quarantinedSizes().size())
                    .append(" — ").append(String.join(", ", status.quarantinedSizes())).append('\n');
        }

        sb.append("Тумблеры: Selenium-подтверждение ").append(onOff(monitor.isConfirmRestockViaSelenium()))
                .append(", burst ").append(onOff(monitor.isBurstConfirm()))
                .append(", анти-флап ").append(monitor.getAntiFlapCooldownTicks() > 0
                        ? "вкл (cooldown " + monitor.getAntiFlapCooldownTicks() + ")" : "выкл").append('\n');

        sb.append("Охват: ").append(this.subscriptionService.activeProductKeys().size()).append(" товаров");

        return sb.toString();
    }

    private String onOff(final boolean enabled) {
        return enabled ? "вкл" : "выкл";
    }

    public String renderRecent() {
        return renderRecent(System.currentTimeMillis());
    }

    String renderRecent(final long now) {
        final var events = this.recentEvents.recent(20);
        if (events.isEmpty()) {
            return "🕓 Последние события\n(пока пусто)";
        }
        final var sb = new StringBuilder("🕓 Последние события:\n");
        for (final var event : events) {
            sb.append(ago(now, event.at())).append(" назад · ").append(event.text()).append('\n');
        }
        return sb.toString();
    }

    private String ago(final long now, final long at) {
        final var sec = Math.max(0, (now - at) / 1000);
        if (sec < 60) {
            return sec + "с";
        }
        final var min = sec / 60;
        return min < 60 ? min + "м" : (min / 60) + "ч";
    }
}

