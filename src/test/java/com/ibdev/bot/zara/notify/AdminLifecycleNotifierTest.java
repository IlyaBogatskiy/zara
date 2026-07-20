package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminLifecycleNotifierTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private AdminNotifier adminNotifier;

    @InjectMocks
    private AdminLifecycleNotifier notifier;

    @Test
    void startupPingReportsProductAndDistinctChatCounts() {
        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of("A", "B")));
        when(subscriptionService.getSubscribersByProduct("A")).thenReturn(Map.of(1L, Set.of("S"), 2L, Set.of("M")));
        when(subscriptionService.getSubscribersByProduct("B")).thenReturn(Map.of(2L, Set.of("L"), 3L, Set.of("XL")));

        notifier.onStartup();

        verify(adminNotifier).notice(contains("2 товар"));
        verify(adminNotifier).notice(contains("3 чат"));
    }

    @Test
    void shutdownPingIsSent() {
        notifier.onShutdown();

        verify(adminNotifier).notice(contains("останавлив"));
    }
}
