package com.ibdev.bot.zara.service.page;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.AdminNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;

/**
 * Tracks how often the fast JSON API path serves a request versus falls back to Selenium, over a
 * sliding window of recent attempts. When the fallback rate over a full window crosses the configured
 * threshold, the admin chat is alerted (throttled by {@link AdminNotifier}) — an early warning that
 * Akamai is tightening and the bot is limping on the slow Selenium path while it still technically
 * works. A single transient API error never trips it; only a sustained rate does.
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ApiHealthTracker {

    private final ZaraProperties properties;
    private final AdminNotifier adminNotifier;

    private final ArrayDeque<Boolean> window = new ArrayDeque<>();

    private final Object breakerLock = new Object();
    private int consecutiveFailures;
    private long openUntil;

    /**
     * Records one API attempt ({@code servedByApi} = the API returned usable data; false = it errored
     * or gave nothing and the caller fell back to Selenium) and, once the window is full and the
     * fallback rate exceeds the threshold, alerts the admin. No-op when the watchdog is disabled.
     */
    public void recordApiOutcome(final boolean servedByApi) {
        recordApiOutcome(servedByApi, System.currentTimeMillis());
    }

    void recordApiOutcome(final boolean servedByApi, final long now) {
        updateBreaker(servedByApi, now);
        updateWindow(servedByApi);
    }

    /**
     * Circuit breaker: whether the API is worth trying right now. Open (in cooldown after repeated
     * failures) → false, so the caller goes straight to Selenium and monitoring never stalls. When the
     * cooldown has elapsed it returns true again for a single re-probe. Disabled → always true.
     */
    public boolean shouldTryApi() {
        return shouldTryApi(System.currentTimeMillis());
    }

    boolean shouldTryApi(final long now) {
        if (this.properties.getApi().getBreakerTripThreshold() <= 0) {
            return true;
        }
        synchronized (this.breakerLock) {
            return this.openUntil == 0 || now >= this.openUntil;
        }
    }

    private void updateBreaker(final boolean servedByApi, final long now) {
        final var threshold = this.properties.getApi().getBreakerTripThreshold();
        if (threshold <= 0) {
            return;
        }
        synchronized (this.breakerLock) {
            if (servedByApi) {
                this.consecutiveFailures = 0;
                this.openUntil = 0;
                return;
            }
            this.consecutiveFailures++;
            if (this.consecutiveFailures >= threshold) {
                this.openUntil = now + this.properties.getApi().getBreakerCooldownMs();
                log.warn("API circuit OPEN: {} consecutive API failures — skipping API for {} ms, on Selenium.",
                        this.consecutiveFailures, this.properties.getApi().getBreakerCooldownMs());
            }
        }
    }

    private void updateWindow(final boolean servedByApi) {
        final var size = this.properties.getApi().getDegradedWindow();
        if (size <= 0) {
            return;
        }
        final var threshold = this.properties.getApi().getDegradedThreshold();

        final int fallbacks;
        final int total;
        synchronized (this.window) {
            this.window.addLast(servedByApi);
            while (this.window.size() > size) {
                this.window.pollFirst();
            }
            if (this.window.size() < size) {
                return;
            }
            total = this.window.size();
            fallbacks = (int) this.window.stream().filter(served -> !served).count();
        }

        if ((double) fallbacks / total > threshold) {
            this.adminNotifier.alert("api-degraded", String.format(
                    "🌐 API Zara деградирует: %d из %d последних запросов ушли в Selenium-fallback "
                            + "(>%.0f%%). Возможно, Akamai закручивает гайки — бот жив, но медленнее.",
                    fallbacks, total, threshold * 100));
        }
    }

    /**
     * Point-in-time view of the sliding window for the admin status screen.
     */
    public record Snapshot(int total, int fallbacks, boolean breakerOpen) {
        public double fallbackRate() {
            return this.total == 0 ? 0.0 : (double) this.fallbacks / this.total;
        }
    }

    public Snapshot snapshot() {
        final int total;
        final int fallbacks;
        synchronized (this.window) {
            total = this.window.size();
            fallbacks = (int) this.window.stream().filter(served -> !served).count();
        }
        final boolean open;
        synchronized (this.breakerLock) {
            open = this.openUntil != 0 && System.currentTimeMillis() < this.openUntil;
        }
        return new Snapshot(total, fallbacks, open);
    }
}
