package com.ibdev.bot.zara.service.page;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.AdminNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
class ApiHealthTrackerTest {

    @Mock
    private AdminNotifier adminNotifier;

    private ApiHealthTracker tracker(final int window, final double threshold) {
        final var props = new ZaraProperties();
        props.getApi().setDegradedWindow(window);
        props.getApi().setDegradedThreshold(threshold);
        return new ApiHealthTracker(props, adminNotifier);
    }

    private void feed(final ApiHealthTracker tracker, final boolean served, final int times) {
        for (int i = 0; i < times; i++) {
            tracker.recordApiOutcome(served);
        }
    }

    @Test
    void doesNotAlertWhileTheApiIsHealthy() {
        final var tracker = tracker(4, 0.5);

        feed(tracker, true, 10);

        verify(adminNotifier, never()).alert(any(), any());
    }

    @Test
    void doesNotAlertBeforeTheWindowIsFull() {
        final var tracker = tracker(4, 0.5);

        feed(tracker, false, 3);

        verify(adminNotifier, never()).alert(any(), any());
    }

    @Test
    void alertsWhenFallbackRateExceedsThreshold() {
        final var tracker = tracker(4, 0.5);

        feed(tracker, true, 1);
        feed(tracker, false, 3);

        verify(adminNotifier).alert(eq("api-degraded"), any());
    }

    @Test
    void doesNotAlertAtOrBelowThreshold() {
        final var tracker = tracker(4, 0.5);

        feed(tracker, true, 2);
        feed(tracker, false, 2);

        verify(adminNotifier, never()).alert(any(), any());
    }

    @Test
    void disabledWhenWindowIsZero() {
        final var tracker = tracker(0, 0.5);

        feed(tracker, false, 50);

        verify(adminNotifier, never()).alert(any(), any());
    }
}
