package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.Watch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.WATCH_IN_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyActivitySummaryReporterTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private AdminNotifier adminNotifier;

    private ZaraProperties properties;
    private ActivityStats stats;
    private DailyActivitySummaryReporter reporter;

    @BeforeEach
    void setUp() {
        properties = new ZaraProperties();
        stats = new ActivityStats();
        reporter = new DailyActivitySummaryReporter(subscriptionService, stats, adminNotifier, properties);
    }

    @Test
    void rendersMonitoringFootprintAndDailyCounts() {
        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of("K1", "K2")));
        when(subscriptionService.getActiveWatches("K1"))
                .thenReturn(List.of(new Watch(1L, "S", AWAIT_RESTOCK), new Watch(2L, "M", WATCH_IN_STOCK)));
        when(subscriptionService.getActiveWatches("K2")).thenReturn(List.of(new Watch(1L, "L", AWAIT_RESTOCK)));

        final var text = reporter.render(new ActivityStats.Counts(3, 2, 1, 0));

        assertThat(text).contains(
                "Товаров в мониторинге: 2",
                "Уникальных чатов: 2",
                "Подписок на размеры: 3",
                "ждут рестока: 2",
                "следят: 1",
                "Появлений: 3",
                "Пропаж: 2",
                "Ценовых: 1");
    }

    @Test
    void flushSendsSummaryToAdminAndDrainsTheCounters() {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of());
        stats.record(new NotifyEvent.SizeAppeared("k", "n", "l", "S", false));

        reporter.flush();

        verify(adminNotifier).notice(contains("Появлений: 1"));
        assertThat(stats.drain().total()).isZero();
    }

    @Test
    void flushDoesNothingWhenDisabled() {
        properties.getActivitySummary().setEnabled(false);

        reporter.flush();

        verify(adminNotifier, never()).notice(any());
    }
}
