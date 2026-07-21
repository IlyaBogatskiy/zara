package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PageDumper;
import com.ibdev.bot.zara.client.ZaraApiClient;
import com.ibdev.bot.zara.client.ZaraPageClient;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.client.WebDriverFactory;
import com.ibdev.bot.zara.notify.AdminNotifier;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.util.Sizes.equalsSize;

/**
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class SelectorCanary {

    private final SubscriptionService subscriptionService;
    private final ZaraApiClient zaraApiClient;
    private final WebDriverFactory webDriverFactory;
    private final AdminNotifier adminNotifier;
    private final ZaraProperties properties;

    @Scheduled(
            initialDelayString = "${zara.canary.initial-delay-ms:600000}",
            fixedDelayString = "${zara.canary.period-ms:21600000}"
    )
    public void checkSelectors() {
        if (!this.properties.getCanary().isEnabled()) {
            return;
        }

        final var productKeys = this.subscriptionService.activeProductKeys();
        if (productKeys.isEmpty()) {
            log.info("Canary: no active subscriptions, skipping.");
            return;
        }

        final var productKey = productKeys.iterator().next();
        final var ref = this.subscriptionService.getProductRef(productKey);
        if (ref == null) {
            return;
        }

        final Map<String, Boolean> viaApi;
        try {
            final var apiSnapshot = this.zaraApiClient.checkSizesAvailability(ref.link());
            viaApi = apiSnapshot == null ? null : apiSnapshot.sizes();
        } catch (final Exception e) {
            log.info("Canary: API unavailable ({}), Selenium not cross-checked — skipping.", e.getMessage());
            return;
        }
        if (viaApi == null || viaApi.isEmpty()) {
            log.info("Canary: API gave no data, Selenium not cross-checked — skipping.");
            return;
        }

        final var driver = this.webDriverFactory.create();
        final var wait = this.webDriverFactory.createWait(driver);
        try {
            final Map<String, Boolean> viaSelenium;
            try {
                viaSelenium = new ZaraPageClient(driver, wait).checkSizesAvailability(ref.link()).sizes();
            } catch (final Exception e) {
                this.adminNotifier.alert(
                        "canary-selenium",
                        "Канарейка: Selenium-парсинг падает на " + ref.link() + "\nПричина: " + e.getMessage()
                                + "\nПохоже, селекторы устарели — фоллбек-путь мониторинга не работает."
                );
                return;
            }

            final var problems = evaluate(viaApi, viaSelenium);
            if (problems.isEmpty()) {
                log.info("Canary OK: Selenium and API agree on {} ({} sizes).", productKey, viaApi.size() - 1);
            } else {
                PageDumper.dump(driver, "canary-mismatch");
                this.adminNotifier.alert(
                        "canary-mismatch",
                        "Канарейка: Selenium и API разошлись на " + ref.link() + "\n" + String.join("\n", problems)
                                + "\nСохранён дамп страницы (dumps/) для разбора."
                );
            }
        } finally {
            driver.quit();
        }
    }

    /**
     * Compares the API and Selenium availability snapshots and returns the human-readable problems
     * worth alerting on — empty means the two agree. The comparison is on STOCK, not mere key
     * presence: the failure the canary exists to catch is "API sees buyable sizes, Selenium sees
     * none" (the historical silent-monitoring-death, everything read as out-of-stock). A fully
     * sold-out product, where Selenium legitimately collapses to the WHOLE "*" unavailable sentinel
     * while the API still enumerates each size as out-of-stock, is agreement — not a mismatch.
     */
    java.util.List<String> evaluate(final Map<String, Boolean> api, final Map<String, Boolean> selenium) {
        final var apiInStock = api.entrySet().stream()
                .filter(e -> !WHOLE.getSize().equals(e.getKey()))
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();

        if (!sawAnySize(selenium)) {
            if (apiInStock.isEmpty()) {
                return java.util.List.of();
            }
            return java.util.List.of(
                    "Selenium показывает «товар недоступен», но по API в наличии: "
                            + String.join(", ", apiInStock)
                            + " — вероятно бот-челлендж/гео-блок или сломан парсинг наличия."
            );
        }

        final var mismatches = new ArrayList<String>();

        for (final var entry : api.entrySet()) {
            final var size = entry.getKey();
            if (WHOLE.getSize().equals(size)) {
                continue;
            }

            final var seleniumValue = lookup(selenium, size);
            if (seleniumValue == null) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    mismatches.add("• размер " + size + ": в наличии по API, но Selenium его не видит");
                }
            } else if (!seleniumValue.equals(entry.getValue())) {
                mismatches.add("• размер " + size + ": API=" + inStock(entry.getValue())
                        + ", Selenium=" + inStock(seleniumValue));
            }
        }

        return mismatches;
    }

    /**
     * The Selenium "product unavailable" page collapses to the lone WHOLE "*" sentinel — no real
     * size was parsed. Any other key means a size lineup was actually read.
     */
    private boolean sawAnySize(final Map<String, Boolean> selenium) {
        return selenium.keySet().stream().anyMatch(key -> !WHOLE.getSize().equals(key));
    }

    private Boolean lookup(final Map<String, Boolean> state, final String size) {
        for (final var entry : state.entrySet()) {
            if (equalsSize(entry.getKey(), size)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String inStock(final boolean value) {
        return value ? "в наличии" : "нет";
    }
}
