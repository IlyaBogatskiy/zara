package com.ibdev.bot.zara.service.page;

import com.ibdev.bot.zara.client.ZaraApiClient;
import com.ibdev.bot.zara.client.ZaraPageClient;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.client.ZaraParsingException;
import com.ibdev.bot.zara.client.WebDriverFactory;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.ProductSnapshot;
import com.ibdev.bot.zara.notify.AdminNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/**
 * Every method: the lightweight JSON API first (~0.5 s), on any problem —
 * fallback to Selenium (~10–30 s). The API is disabled via zara.api.enabled=false.
 *
 * @author i.bogatskii
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PageServiceImpl implements PageService {

    private final WebDriverFactory webDriverFactory;
    private final ZaraApiClient zaraApiClient;
    private final ZaraProperties properties;
    private final AdminNotifier adminNotifier;

    @Override
    public ProductCard loadProductCard(final String link) {
        if (this.properties.getApi().isEnabled()) {
            try {
                final var viaApi = this.zaraApiClient.loadProductCard(link);
                if (viaApi != null) {
                    return viaApi;
                }
                log.info("Zara API gave no card for {}, falling back to Selenium.", link);
            } catch (final Exception e) {
                log.warn("Zara API card load failed for {} ({}), falling back to Selenium.", link, e.getMessage());
            }
        }

        final var driver = webDriverFactory.create();
        final var wait = webDriverFactory.createWait(driver);

        try {
            final var client = new ZaraPageClient(driver, wait);
            return client.loadProductCard(link);
        } catch (final ZaraParsingException e) {
            this.adminNotifier.alert("selenium-parsing",
                    "Selenium-парсинг карточки сломан: " + e.getMessage() + "\nСсылка: " + link);
            throw e;
        } finally {
            driver.quit();
        }
    }

    @Override
    public ProductSnapshot checkProductSizesAvailability(final String link) {
        if (this.properties.getApi().isEnabled()) {
            try {
                final var viaApi = this.zaraApiClient.checkSizesAvailability(link);
                if (viaApi != null && viaApi.sizes() != null && !viaApi.sizes().isEmpty()) {
                    return viaApi;
                }
                log.info("Zara API gave no data for {}, falling back to Selenium.", link);
            } catch (final Exception e) {
                log.warn("Zara API check failed for {} ({}), falling back to Selenium.", link, e.getMessage());
            }
        }

        final var driver = webDriverFactory.create();
        final var wait = webDriverFactory.createWait(driver);

        try {
            final var client = new ZaraPageClient(driver, wait);
            return client.checkSizesAvailability(link);
        } catch (final ZaraParsingException e) {
            this.adminNotifier.alert("selenium-parsing",
                    "Selenium-проверка наличия сломана: " + e.getMessage() + "\nСсылка: " + link);
            throw e;
        } finally {
            driver.quit();
        }
    }
}
