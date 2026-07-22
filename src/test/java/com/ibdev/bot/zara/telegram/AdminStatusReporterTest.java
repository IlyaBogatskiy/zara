package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.RecentEvents;
import com.ibdev.bot.zara.scheduler.MonitoringScheduler;
import com.ibdev.bot.zara.service.page.ApiHealthTracker;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminStatusReporterTest {

    @Mock
    private MonitoringScheduler scheduler;
    @Mock
    private ApiHealthTracker apiHealthTracker;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private RecentEvents recentEvents;

    private final ZaraProperties properties = new ZaraProperties();

    private AdminStatusReporter reporter() {
        return new AdminStatusReporter(scheduler, apiHealthTracker, properties, subscriptionService, recentEvents);
    }

    @Test
    void rendersTickApiQuarantineTogglesAndFootprint() {
        properties.getMonitor().setConfirmRestockViaSelenium(false);
        properties.getMonitor().setBurstConfirm(true);
        properties.getMonitor().setAntiFlapCooldownTicks(3);
        when(scheduler.status()).thenReturn(new MonitoringScheduler.MonitoringStatus(
                42L, 100_000L, 1500L, List.of("03046090 XL")));
        when(apiHealthTracker.snapshot()).thenReturn(new ApiHealthTracker.Snapshot(20, 5, false));
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of("K1", "K2"));

        final var text = reporter().render(130_000L);

        assertThat(text).contains(
                "Статус мониторинга",
                "Тик #42",
                "30 сек назад",
                "длился 1500 мс",
                "fallback на Selenium 5/20 (25%)",
                "Карантин флапа: 1 — 03046090 XL",
                "Selenium-подтверждение выкл",
                "burst вкл",
                "анти-флап вкл (cooldown 3)",
                "Охват: 2 товаров");
    }

    @Test
    void handlesNoTickAndEmptyApiWindow() {
        when(scheduler.status()).thenReturn(new MonitoringScheduler.MonitoringStatus(0L, 0L, 0L, List.of()));
        when(apiHealthTracker.snapshot()).thenReturn(new ApiHealthTracker.Snapshot(0, 0, false));
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of());

        final var text = reporter().render(1_000L);

        assertThat(text).contains("Тик: ещё не выполнялся", "API-путь: нет данных", "Карантин флапа: нет");
    }

    @Test
    void showsBreakerOpen() {
        when(scheduler.status()).thenReturn(new MonitoringScheduler.MonitoringStatus(1L, 100L, 10L, List.of()));
        when(apiHealthTracker.snapshot()).thenReturn(new ApiHealthTracker.Snapshot(20, 20, true));
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of("K1"));

        assertThat(reporter().render(1_000L)).contains("предохранитель ОТКРЫТ");
    }

    @Test
    void renderRecentShowsNewestFirstWithAgo() {
        when(recentEvents.recent(20)).thenReturn(List.of(
                new RecentEvents.Event(120_000L, "✅ Куртка S появился · чат 1"),
                new RecentEvents.Event(60_000L, "🔧 Канарейка")));

        final var text = reporter().renderRecent(180_000L);

        assertThat(text).contains("Последние события", "1м назад · ✅ Куртка S появился", "2м назад · 🔧 Канарейка");
    }

    @Test
    void renderRecentEmpty() {
        when(recentEvents.recent(20)).thenReturn(List.of());
        assertThat(reporter().renderRecent(1_000L)).contains("пока пусто");
    }
}
