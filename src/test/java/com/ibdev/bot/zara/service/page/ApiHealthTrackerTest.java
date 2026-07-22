package com.ibdev.bot.zara.service.page;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.AdminNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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

    private ApiHealthTracker breaker(final int tripThreshold, final long cooldownMs) {
        final var props = new ZaraProperties();
        props.getApi().setDegradedWindow(0);
        props.getApi().setBreakerTripThreshold(tripThreshold);
        props.getApi().setBreakerCooldownMs(cooldownMs);
        return new ApiHealthTracker(props, adminNotifier);
    }

    @Test
    void breakerClosedInitiallyTriesApi() {
        assertThat(breaker(2, 1000).shouldTryApi(0)).isTrue();
    }

    @Test
    void breakerTripsAfterConsecutiveFailures() {
        final var t = breaker(2, 1000);

        t.recordApiOutcome(false, 0);
        assertThat(t.shouldTryApi(0)).as("1 failure — not yet tripped").isTrue();
        t.recordApiOutcome(false, 0);
        assertThat(t.shouldTryApi(500)).as("2 failures — open during cooldown").isFalse();
    }

    @Test
    void breakerHalfOpensAfterCooldown() {
        final var t = breaker(2, 1000);
        t.recordApiOutcome(false, 0);
        t.recordApiOutcome(false, 0);

        assertThat(t.shouldTryApi(500)).isFalse();
        assertThat(t.shouldTryApi(1000)).as("cooldown elapsed — re-probe").isTrue();
    }

    @Test
    void breakerClosesOnSuccessfulProbe() {
        final var t = breaker(2, 1000);
        t.recordApiOutcome(false, 0);
        t.recordApiOutcome(false, 0);

        t.recordApiOutcome(true, 1000);

        assertThat(t.shouldTryApi(1001)).isTrue();
    }

    @Test
    void breakerReopensOnFailedProbe() {
        final var t = breaker(2, 1000);
        t.recordApiOutcome(false, 0);
        t.recordApiOutcome(false, 0);

        t.recordApiOutcome(false, 1000);

        assertThat(t.shouldTryApi(1500)).as("failed probe re-opens").isFalse();
        assertThat(t.shouldTryApi(2000)).isTrue();
    }

    @Test
    void breakerDisabledWhenThresholdZero() {
        final var t = breaker(0, 1000);

        t.recordApiOutcome(false, 0);
        t.recordApiOutcome(false, 0);
        t.recordApiOutcome(false, 0);

        assertThat(t.shouldTryApi(0)).isTrue();
    }
}
