package com.ibdev.bot.zara.scheduler;

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
 * Canary for CSS selector breakage. The treacherous part of the Selenium path:
 * a broken selector used to look exactly like "everything is out of stock" and
 * notifications simply stopped coming. The canary periodically runs Selenium
 * parsing on a live monitored product and cross-checks the result against an
 * independent source — the JSON API. A mismatch or a parse failure = operator alert.
 *
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

        final Map<String, Boolean> viaSelenium;
        try {
            viaSelenium = checkViaSelenium(ref.link());
        } catch (final Exception e) {
            this.adminNotifier.alert(
                    "canary-selenium",
                    "Канарейка: Selenium-парсинг падает на " + ref.link() + "\nПричина: " + e.getMessage()
                            + "\nПохоже, селекторы устарели — фоллбек-путь мониторинга не работает."
            );
            return;
        }

        final Map<String, Boolean> viaApi;
        try {
            viaApi = this.zaraApiClient.checkSizesAvailability(ref.link());
        } catch (final Exception e) {
            log.info("Canary: API unavailable ({}), but Selenium parsing works — OK.", e.getMessage());
            return;
        }
        if (viaApi == null || viaApi.isEmpty()) {
            log.info("Canary: API gave no data, but Selenium parsing works — OK.");
            return;
        }

        final var mismatches = compare(viaApi, viaSelenium);
        if (mismatches.isEmpty()) {
            log.info("Canary OK: Selenium and API agree on {} ({} sizes).", productKey, viaApi.size() - 1);
        } else {
            this.adminNotifier.alert(
                    "canary-mismatch",
                    "Канарейка: Selenium и API разошлись на " + ref.link() + "\n" + String.join("\n", mismatches)
                            + "\nВозможно, частично сломаны селекторы (например, признак наличия)."
            );
        }
    }

    private Map<String, Boolean> checkViaSelenium(final String link) {
        final var driver = this.webDriverFactory.create();
        final var wait = this.webDriverFactory.createWait(driver);
        try {
            return new ZaraPageClient(driver, wait).checkSizesAvailability(link);
        } finally {
            driver.quit();
        }
    }

    private java.util.List<String> compare(final Map<String, Boolean> api, final Map<String, Boolean> selenium) {
        final var mismatches = new ArrayList<String>();

        for (final var entry : api.entrySet()) {
            final var size = entry.getKey();
            if (WHOLE.getSize().equals(size)) {
                continue;
            }

            final var seleniumValue = lookup(selenium, size);
            if (seleniumValue == null) {
                mismatches.add("• размер " + size + ": есть в API, не найден Selenium-парсингом");
            } else if (!seleniumValue.equals(entry.getValue())) {
                mismatches.add("• размер " + size + ": API=" + inStock(entry.getValue())
                        + ", Selenium=" + inStock(seleniumValue));
            }
        }

        return mismatches;
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
