package com.ibdev.bot.zara.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the externalized {@code zara.selectors.*} defaults to the last-known-good Zara markup. The
 * Selenium parser ({@code ZaraPageClient}) needs a real Chrome and isn't unit-tested, so a drifted or
 * mistyped default selector would silently break monitoring — this guard catches an accidental change.
 *
 * @author i.bogatskii
 */
class ZaraPropertiesSelectorsTest {

    private final ZaraProperties.Selectors selectors = new ZaraProperties().getSelectors();

    @Test
    void defaultsMatchKnownGoodMarkup() {
        assertThat(selectors.getProductName()).isEqualTo("h1[data-qa-qualifier='product-detail-info-name']");
        assertThat(selectors.getAddToCart()).isEqualTo("button[data-qa-action='add-to-cart']");
        assertThat(selectors.getViewSimilar()).isEqualTo("button[data-qa-action='show-similar-products']");
        assertThat(selectors.getSizeRow()).isEqualTo("ul.size-selector-sizes > li");
        assertThat(selectors.getSizeLabel()).isEqualTo("div[data-qa-qualifier='size-selector-sizes-size-label']");
        assertThat(selectors.getSizeButton()).isEqualTo("button[data-qa-action^='size-']");
        assertThat(selectors.getCookieAccept()).isEqualTo("button#onetrust-accept-btn-handler");
        assertThat(selectors.getPriceCurrent())
                .isEqualTo("[data-qa-qualifier='price-amount-current'], .price__amount--current .money-amount__main");
        assertThat(selectors.getInStockMarker()).isEqualTo("in-stock");
    }
}
