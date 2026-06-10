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

    /** User-Agent shared by both paths — Selenium and the HTTP API. */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** Operator chatId for technical alerts (selector breakage); null/0 — log only. */
    private Long adminChatId;

    private final Api api = new Api();
    private final Driver driver = new Driver();
    private final Canary canary = new Canary();

    @Getter
    @Setter
    public static class Api {
        /** The fast JSON path (products-details). false — everything goes through Selenium. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Driver {
        /** WebDriverWait timeout, in seconds. */
        private int waitSeconds = 15;

        /** Remote WebDriver URL (Selenium Grid / standalone-chrome); blank — local ChromeDriver. */
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
}
