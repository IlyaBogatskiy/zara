package com.ibdev.bot.zara.scheduler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour spec for the Selenium ↔ API cross-check decision (SelectorCanary.evaluate).
 * The comparison must be on STOCK, not mere key presence: a fully sold-out product, where
 * Selenium collapses to the WHOLE "*" unavailable sentinel while the API still enumerates each
 * size as out-of-stock, is agreement — not a selector mismatch. See
 * docs/reports/2026-07-21_canary-false-alarm-on-soldout.md.
 *
 * @author i.bogatskii
 */
class SelectorCanaryTest {

    private static final String WHOLE = "*";

    private final SelectorCanary canary = new SelectorCanary(null, null, null, null, null);

    private static Map<String, Boolean> sizes(final Object... pairs) {
        final var map = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return map;
    }

    /**
     * The reported incident: product fully sold out, Selenium shows the "product unavailable" page
     * and returns only {"*": false}, the API still lists every size as out-of-stock. They agree —
     * nothing is buyable — so the canary must stay silent.
     */
    @Test
    void agreesWhenProductFullySoldOut() {
        final var api = sizes("XS", false, "S", false, "M", false, "L", false, "XL", false, WHOLE, false);
        final var selenium = sizes(WHOLE, false);

        assertThat(canary.evaluate(api, selenium)).isEmpty();
    }

    /**
     * A size the API reports out-of-stock that Selenium simply didn't enumerate is noise, not a
     * selector failure — the size isn't buyable either way. No alert.
     */
    @Test
    void ignoresMissingOutOfStockSize() {
        final var api = sizes("XS", false, "S", true, WHOLE, true);
        final var selenium = sizes("S", true, WHOLE, true);

        assertThat(canary.evaluate(api, selenium)).isEmpty();
    }

    /**
     * The failure that actually matters: the API sees buyable stock while Selenium shows the whole
     * product as unavailable — likely a bot-challenge / geo-block or a broken availability parse.
     * Must alert, and the message must point at "unavailable", not the old "not found" phrasing.
     */
    @Test
    void alertsWhenSeleniumBlindButApiHasStock() {
        final var api = sizes("XS", true, "S", false, "M", false, "L", false, "XL", false, WHOLE, true);
        final var selenium = sizes(WHOLE, false);

        final var problems = canary.evaluate(api, selenium);

        assertThat(problems).isNotEmpty();
        assertThat(String.join("\n", problems))
                .contains("XS")
                .contains("недоступ");
    }

    /**
     * A genuine per-size stock disagreement — API in stock, Selenium out of stock — is the classic
     * selector-drift signal and must alert.
     */
    @Test
    void alertsOnPerSizeStockMismatch() {
        final var api = sizes("M", true, WHOLE, true);
        final var selenium = sizes("M", false, WHOLE, false);

        final var problems = canary.evaluate(api, selenium);

        assertThat(problems).isNotEmpty();
        assertThat(String.join("\n", problems)).contains("M");
    }

    /**
     * When the stock matches size by size, there is nothing to report.
     */
    @Test
    void silentWhenStockAgrees() {
        final var api = sizes("M", true, "L", false, WHOLE, true);
        final var selenium = sizes("M", true, "L", false, WHOLE, true);

        assertThat(canary.evaluate(api, selenium)).isEmpty();
    }
}
