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

    /**
     * Records one API attempt ({@code servedByApi} = the API returned usable data; false = it errored
     * or gave nothing and the caller fell back to Selenium) and, once the window is full and the
     * fallback rate exceeds the threshold, alerts the admin. No-op when the watchdog is disabled.
     */
    public void recordApiOutcome(final boolean servedByApi) {
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
}
