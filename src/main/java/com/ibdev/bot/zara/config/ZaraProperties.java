package com.ibdev.bot.zara.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All bot knobs in one place (application.yaml, prefix "zara").
 *
 * @author i.bogatskii
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "zara")
public class ZaraProperties {

    /**
     * User-Agent shared by both paths — Selenium and the HTTP API.
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Operator chatId for technical alerts (selector breakage); null/0 — log only.
     */
    private Long adminChatId;

    private final Api api = new Api();
    private final Driver driver = new Driver();
    private final Canary canary = new Canary();
    private final Monitor monitor = new Monitor();

    @Getter
    @Setter
    public static class Api {
        /**
         * The fast JSON path (products-details). false — everything goes through Selenium.
         */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Driver {
        /**
         * WebDriverWait timeout, in seconds.
         */
        private int waitSeconds = 15;

        /**
         * Remote WebDriver URL (Selenium Grid / standalone-chrome); blank — local ChromeDriver.
         */
        private String remoteUrl = "";
    }

    @Getter
    @Setter
    public static class Canary {
        /**
         * Periodic cross-check of Selenium parsing against the JSON API on a live
         * product: catches selector breakage before the fallback is actually needed.
         */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Monitor {
        /**
         * Debounce: how many consecutive checks must agree before an availability change is
         * committed and notified. 2 filters CDN/edge-cache blips and "low stock, sold in seconds";
         * 1 restores the old immediate behaviour.
         */
        private int confirmations = 2;

        /**
         * Before notifying a size/product came back in stock, cross-check the transition with the
         * Selenium/DOM path (only when the primary reading came from the API). The DOM detects the
         * real "unavailable" page that a stale API {@code in_stock} can lie about.
         * <p>
         * <b>Off by default</b> (opt-in): this runs a heavy Selenium scrape <em>synchronously on the
         * single-threaded scheduler</em>, which can block the whole tick for 10–120 s and, if the
         * Selenium/DOM path is even slightly off, silently suppress real alerts. Enable only with a
         * healthy, fast Selenium backend. The debounce ({@link #confirmations}) already filters blips.
         */
        private boolean confirmRestockViaSelenium = false;

        /**
         * Burst-confirm: when a watched size first disagrees with the confirmed state, fetch the
         * missing confirming observation(s) immediately (a fast API re-scrape after a short pause)
         * instead of waiting a whole {@code period-ms} for the next scheduled tick. Cuts the
         * confirmation half of the notification latency from ~one period to ~{@link #burstConfirmDelayMs}.
         * Trade-off: the two reads are only seconds apart, so slightly less independent than the
         * cross-tick debounce (may echo a stale CDN edge); the cross-tick debounce stays as the backstop.
         */
        private boolean burstConfirm = true;

        /**
         * Pause before each confirming re-scrape (ms) — long enough to have a chance at a refreshed
         * CDN reading, short enough to keep the latency win.
         */
        private long burstConfirmDelayMs = 3000;

        /**
         * Cap on how many products may be burst-confirmed within a single tick, so a pathological
         * "everything changed at once" tick cannot balloon; the rest confirm via the normal debounce.
         */
        private int burstConfirmMaxPerTick = 3;

        /**
         * Anti-flap cooldown, in ticks (≈ periods). Once a size's availability commits a flip, it is
         * barred from {@link #burstConfirm} for this many subsequent ticks — its next flip must earn
         * the independent cross-tick debounce, not an immediate (same-CDN-edge) re-scrape. The same
         * window is the flap-detection window: an opposite flip committed within it marks the size as a
         * flapper and quarantines it (see {@link #flapQuarantineTicks}).
         * <p>
         * <b>0 disables the whole anti-flap machinery</b> (leaving pure debounce + burst-confirm) —
         * the default, opt-in like {@link #confirmRestockViaSelenium}, because a quarantine can also
         * mute a <em>genuine</em> rapid restock. It fixes the residual flap where a genuinely-OOS
         * product's API reading flickers in-stock for a few seconds: the burst re-scrape
         * ({@code burstConfirmDelayMs} later) echoes the same stale edge and commits a false restock,
         * which the next tick reverts — SOLD_OUT/APPEARED oscillating every ~1-2 periods.
         */
        private int antiFlapCooldownTicks = 0;

        /**
         * How many ticks (≈ periods) a size stays quarantined once detected as a flapper: while
         * quarantined its restocks are muted (reverted, no alert), so a genuinely-OOS product that
         * keeps flickering in-stock produces at most one false appeared/sold-out pair, then goes
         * quiet. Only consulted when {@link #antiFlapCooldownTicks} > 0. After it elapses a fresh
         * genuine restock alerts normally again.
         */
        private int flapQuarantineTicks = 60;
    }
}
