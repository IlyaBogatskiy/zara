package com.ibdev.bot.zara.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.util.*;
import java.util.NoSuchElementException;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.util.ProductLinks.extractProductId;
import static java.lang.Boolean.*;

/**
 * @author i.bogatskii
 */
@Log4j2
@RequiredArgsConstructor
public class ZaraPageClient {

    private static final By PRODUCT_NAME = By.cssSelector("h1[data-qa-qualifier='product-detail-info-name']");
    private static final By ADD_TO_CART_BTN = By.cssSelector("button[data-qa-action='add-to-cart']");
    private static final By VIEW_SIMILAR_BTN = By.cssSelector("button[data-qa-action='show-similar-products']");
    private static final By SIZE_LI = By.cssSelector("ul.size-selector-sizes > li");
    private static final By SIZE_LABEL = By.cssSelector("div[data-qa-qualifier='size-selector-sizes-size-label']");
    private static final By SIZE_BTN = By.cssSelector("button[data-qa-action^='size-']");
    private static final By ONE_TRUST_BTN = By.cssSelector("button#onetrust-accept-btn-handler");
    private static final By PRICE_CURRENT = By.cssSelector(
            "[data-qa-qualifier='price-amount-current'], .price__amount--current .money-amount__main");

    /**
     * In-stock marker within the size button's data-qa-action (e.g. "size-in-stock").
     */
    private static final String IN_STOCK_MARKER = "in-stock";

    /**
     * "17.97 EUR" / "1 797 RSD" → numeric group + currency-letters group.
     */
    private static final java.util.regex.Pattern PRICE_TEXT =
            java.util.regex.Pattern.compile("([\\d.,\\s]+)\\s*([A-Za-z]{2,4})?");

    private final WebDriver webDriver;
    private final WebDriverWait webDriverWait;

    public ProductCard loadProductCard(final String link) {
        this.webDriver.get(link);

        final var dto = new ProductCard();
        dto.setName(readProductName());
        dto.setProductKey(extractProductId(link));
        dto.setLink(link);

        if (isProductUnavailable()) {
            dto.setSizeDetails(List.of(new SizeInfo(WHOLE.getSize(), false)));
            return dto;
        }

        waitPageLoaded();
        acceptCookiesIfPresent();

        dto.setPrice(readPrice());
        openSizesPopup();

        dto.setSizeDetails(readAllSizeDetails());

        return dto;
    }

    public ProductSnapshot checkSizesAvailability(final String link) {
        this.webDriver.get(link);

        if (isProductUnavailable()) {
            return new ProductSnapshot(Map.of(WHOLE.getSize(), false), null);
        }

        waitPageLoaded();
        acceptCookiesIfPresent();

        final var price = readPrice();
        openSizesPopup();

        final var sizeLis = this.webDriver.findElements(SIZE_LI);

        if (sizeLis.isEmpty()) {
            PageDumper.dump(this.webDriver, "no-size-rows");
            throw new ZaraParsingException(
                    "Sizes popup opened but no rows matched the size selector. Page: " + link
            );
        }

        final var state = new LinkedHashMap<String, Boolean>(sizeLis.size());
        var anyInStock = false;

        for (final var li : sizeLis) {
            try {
                final var label = li.findElement(SIZE_LABEL).getText().trim();
                final var inStock = isInStock(li.findElement(SIZE_BTN));
                state.put(label, inStock);
                if (inStock) {
                    anyInStock = true;
                }
            } catch (final org.openqa.selenium.NoSuchElementException e) {
                PageDumper.dump(this.webDriver, "size-row-selectors");
                throw new ZaraParsingException(
                        "Size row doesn't match label/button selectors. Page: " + link, e
                );
            }
        }

        state.put(WHOLE.getSize(), anyInStock);

        return new ProductSnapshot(state, price);
    }

    /**
     * Best-effort current price. Price tracking is a sales nicety, not core
     * monitoring, so any miss returns null instead of throwing — the API path is
     * the reliable price source; this only matters when Selenium is the fallback.
     */
    private PriceInfo readPrice() {
        try {
            final var elements = this.webDriver.findElements(PRICE_CURRENT);
            if (elements.isEmpty()) {
                return null;
            }
            return parsePriceText(elements.getFirst().getText());
        } catch (final Exception e) {
            return null;
        }
    }

    private PriceInfo parsePriceText(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        final var matcher = PRICE_TEXT.matcher(raw.trim());
        if (!matcher.find()) {
            return null;
        }

        final var number = matcher.group(1).replaceAll("\\s", "");
        final var currency = matcher.group(2);

        final var separator = Math.max(number.lastIndexOf('.'), number.lastIndexOf(','));
        final var fractionDigits = separator < 0 ? 0 : number.length() - separator - 1;
        final var digitsOnly = number.replaceAll("[.,]", "");
        if (digitsOnly.isEmpty()) {
            return null;
        }

        return new PriceInfo(Long.parseLong(digitsOnly), currency == null ? null : currency.toUpperCase(), fractionDigits);
    }


    private boolean isProductUnavailable() {
        return !this.webDriver.findElements(VIEW_SIMILAR_BTN).isEmpty()
                && this.webDriver.findElements(ADD_TO_CART_BTN).isEmpty();
    }

    private void acceptCookiesIfPresent() {
        try {
            final var cookiesBtn = webDriver.findElement(ONE_TRUST_BTN);

            if (cookiesBtn.isDisplayed()) {
                cookiesBtn.click();
            }

        } catch (Exception ignored) {
        }
    }

    private void waitPageLoaded() {
        this.webDriverWait.until(driver ->
                Objects.equals(((JavascriptExecutor) driver)
                        .executeScript("return document.readyState"), "complete")
        );
    }

    private void openSizesPopup() {
        try {
            final var addButton = this.webDriverWait.until(driver -> {
                try {
                    final var btn = driver.findElement(ADD_TO_CART_BTN);
                    return btn.isDisplayed() ? btn : null;
                } catch (final NoSuchElementException e) {
                    return null;
                }
            });

            this.webDriverWait.until(driver -> addButton.isEnabled());

            try {
                addButton.click();
            } catch (final ElementClickInterceptedException | StaleElementReferenceException e) {
                ((JavascriptExecutor) this.webDriver).executeScript("arguments[0].click();", addButton);
            }

            this.webDriverWait.until(ExpectedConditions.presenceOfElementLocated(SIZE_LI));
        } catch (final org.openqa.selenium.TimeoutException e) {
            PageDumper.dump(this.webDriver, "no-add-to-cart");
            throw new ZaraParsingException(
                    "Add-to-cart button or size list did not appear in time — selectors may be outdated.", e
            );
        }
    }

    private List<SizeInfo> readAllSizeDetails() {
        final var sizeLis = this.webDriver.findElements(SIZE_LI);
        final var result = new ArrayList<SizeInfo>();

        for (final var li : sizeLis) {
            final var btn = li.findElement(SIZE_BTN);
            if (!isInStock(btn)) {
                result.add(mapOutOfStockSize(li));
            }
        }

        return result;
    }

    private SizeInfo mapOutOfStockSize(final WebElement li) {
        final var sizeLabel = li.findElement(SIZE_LABEL).getText().trim();
        return new SizeInfo(sizeLabel, false);
    }

    private boolean isInStock(final WebElement sizeButton) {
        final var qaAction = sizeButton.getAttribute("data-qa-action");
        final var inStock = qaAction != null && qaAction.contains(IN_STOCK_MARKER);

        final var ariaDisabled = sizeButton.getAttribute("aria-disabled");
        if (TRUE.toString().equalsIgnoreCase(ariaDisabled) || !sizeButton.isEnabled()) {
            return false;
        }

        return inStock;
    }

    private String readProductName() {
        return this.webDriverWait
                .until(ExpectedConditions.visibilityOfElementLocated(PRODUCT_NAME))
                .getText()
                .trim()
                .replaceAll("\\s+", " ");
    }
}
