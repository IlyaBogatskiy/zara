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
    private final LogDigest logDigest = new LogDigest();
    private final ActivitySummary activitySummary = new ActivitySummary();
    private final Selectors selectors = new Selectors();

    @Getter
    @Setter
    public static class Api {
        /**
         * The fast JSON path (products-details). false — everything goes through Selenium.
         */
        private boolean enabled = true;

        /**
         * API-degradation watchdog: the size of the sliding window of recent API attempts over which
         * the fallback-to-Selenium rate is measured. When the rate exceeds {@link #degradedThreshold}
         * the admin chat is alerted (throttled) — an early sign Akamai is tightening and the bot is
         * limping on the slow Selenium path. 0 disables the watchdog.
         */
        private int degradedWindow = 20;

        /**
         * Fraction (0..1) of the window that must have fallen back to Selenium before the degradation
         * alert fires.
         */
        private double degradedThreshold = 0.5;
    }

    @Getter
    @Setter
    public static class Driver {
        /**
         * WebDriverWait timeout, in seconds.
         */
        private int waitSeconds = 15;

        /**
         * Hard cap on a single page load ({@code driver.get}), in seconds. Bounds a hung Selenium
         * scrape (challenge page / stalled network) so it fails fast instead of blocking the
         * single-threaded scheduler for the driver's default (~300 s). 0 disables the cap. Keep it
         * above a healthy page load (a few seconds) yet well under the observed 50–120 s stalls.
         */
        private int pageLoadTimeoutSeconds = 20;

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

        /**
         * Watchdog: if the gap since the previous completed tick exceeds this (ms), the resuming tick
         * reports a monitoring outage to the admin chat — the window when stock changes were missed
         * (e.g. the host was suspended). Set well above one period so normal jitter never trips it.
         * 0 disables the gap alert.
         */
        private long stallAlertMs = 300_000;

        /**
         * If a single tick takes longer than this (ms) — almost always a synchronous Selenium fallback
         * blocking the single-threaded scheduler — alert the admin chat (throttled). 0 disables it.
         */
        private long slowTickAlertMs = 15_000;

        /**
         * How many products a tick scrapes concurrently. Bounds parallel Chrome instances when scrapes
         * fall back to Selenium, so one slow product no longer blocks the rest of the tick (the scrape
         * itself is still capped by {@code driver.page-load-timeout-seconds}). 1 keeps the old
         * single-threaded scan; a tick with a single product never spins up a pool regardless.
         */
        private int scanThreads = 4;
    }

    @Getter
    @Setter
    public static class LogDigest {
        /**
         * Daily WARN/ERROR digest: a bounded in-memory appender collects every log event at level
         * WARN or above across all loggers, and a scheduled job flushes them once a day as a text
         * file to the admin Telegram chat — so problems that were never surfaced by a dedicated alert
         * still get seen. Nothing is sent on a clean day (no heartbeat).
         */
        private boolean enabled = true;

        /**
         * When the digest is flushed (Spring cron). Default: 23:55 daily — end of day.
         */
        private String cron = "0 55 23 * * *";

        /**
         * Time zone for the cron and for timestamps in the digest. Blank = the JVM default zone.
         */
        private String zone = "";

        /**
         * Ring-buffer cap: the newest N WARN/ERROR events are kept; older ones are dropped (their
         * count is reported at the top of the digest). Bounds memory between flushes.
         */
        private int maxEntries = 5000;
    }

    @Getter
    @Setter
    public static class ActivitySummary {
        /**
         * Daily "how the bot lived" report to the admin chat: monitoring footprint + the day's event
         * counts (appeared/sold-out/price/whole). Always sent — it is the report, not an alert.
         */
        private boolean enabled = true;

        /**
         * When the summary is sent (Spring cron). Default: 09:00 daily.
         */
        private String cron = "0 0 9 * * *";

        /**
         * Time zone for the cron. Blank = the JVM default zone.
         */
        private String zone = "";
    }

    /**
     * CSS selectors for the Selenium fallback ({@code ZaraPageClient}), externalized so a Zara DOM
     * change can be hotfixed via env/yaml without recompiling. Defaults are the last-known-good markup.
     */
    @Getter
    @Setter
    public static class Selectors {
        private String productName = "h1[data-qa-qualifier='product-detail-info-name']";
        private String addToCart = "button[data-qa-action='add-to-cart']";
        private String viewSimilar = "button[data-qa-action='show-similar-products']";
        private String sizeRow = "ul.size-selector-sizes > li";
        private String sizeLabel = "div[data-qa-qualifier='size-selector-sizes-size-label']";
        private String sizeButton = "button[data-qa-action^='size-']";
        private String cookieAccept = "button#onetrust-accept-btn-handler";
        private String priceCurrent =
                "[data-qa-qualifier='price-amount-current'], .price__amount--current .money-amount__main";

        /**
         * Substring in the size button's data-qa-action that marks it in stock (e.g. "size-in-stock").
         */
        private String inStockMarker = "in-stock";
    }
}
