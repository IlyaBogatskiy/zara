package com.ibdev.bot.zara.client;

import com.ibdev.bot.zara.config.ZaraProperties;
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
public class ZaraPageClient {

    /**
     * "17.97 EUR" / "1 797 RSD" → numeric group + currency-letters group.
     */
    private static final java.util.regex.Pattern PRICE_TEXT =
            java.util.regex.Pattern.compile("([\\d.,\\s]+)\\s*([A-Za-z]{2,4})?");

    private final WebDriver webDriver;
    private final WebDriverWait webDriverWait;

    private final By productName;
    private final By addToCart;
    private final By viewSimilar;
    private final By sizeRow;
    private final By sizeLabel;
    private final By sizeButton;
    private final By cookieAccept;
    private final By priceCurrent;

    /**
     * In-stock marker within the size button's data-qa-action (e.g. "size-in-stock").
     */
    private final String inStockMarker;

    /**
     * Selectors come from configuration ({@code zara.selectors.*}) so a Zara DOM change can be
     * hotfixed via env/yaml without recompiling; the defaults match the last-known-good markup.
     */
    public ZaraPageClient(final WebDriver webDriver, final WebDriverWait webDriverWait,
                          final ZaraProperties.Selectors selectors) {
        this.webDriver = webDriver;
        this.webDriverWait = webDriverWait;
        this.productName = By.cssSelector(selectors.getProductName());
        this.addToCart = By.cssSelector(selectors.getAddToCart());
        this.viewSimilar = By.cssSelector(selectors.getViewSimilar());
        this.sizeRow = By.cssSelector(selectors.getSizeRow());
        this.sizeLabel = By.cssSelector(selectors.getSizeLabel());
        this.sizeButton = By.cssSelector(selectors.getSizeButton());
        this.cookieAccept = By.cssSelector(selectors.getCookieAccept());
        this.priceCurrent = By.cssSelector(selectors.getPriceCurrent());
        this.inStockMarker = selectors.getInStockMarker();
    }

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

        final var sizeLis = this.webDriver.findElements(sizeRow);

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
                final var label = li.findElement(sizeLabel).getText().trim();
                final var inStock = isInStock(li.findElement(sizeButton));
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
            final var elements = this.webDriver.findElements(priceCurrent);
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
        return !this.webDriver.findElements(viewSimilar).isEmpty()
                && this.webDriver.findElements(addToCart).isEmpty();
    }

    private void acceptCookiesIfPresent() {
        try {
            final var cookiesBtn = webDriver.findElement(cookieAccept);

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
                    final var btn = driver.findElement(addToCart);
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

            this.webDriverWait.until(ExpectedConditions.presenceOfElementLocated(sizeRow));
        } catch (final org.openqa.selenium.TimeoutException e) {
            PageDumper.dump(this.webDriver, "no-add-to-cart");
            throw new ZaraParsingException(
                    "Add-to-cart button or size list did not appear in time — selectors may be outdated.", e
            );
        }
    }

    /**
     * The FULL size lineup with real availability (in-stock sizes included), so the user
     * can pick any size to track — not just the out-of-stock ones.
     */
    private List<SizeInfo> readAllSizeDetails() {
        final var sizeLis = this.webDriver.findElements(sizeRow);
        final var result = new ArrayList<SizeInfo>();

        for (final var li : sizeLis) {
            final var label = li.findElement(sizeLabel).getText().trim();
            final var inStock = isInStock(li.findElement(sizeButton));
            result.add(new SizeInfo(label, inStock));
        }

        return result;
    }

    private boolean isInStock(final WebElement sizeButton) {
        final var qaAction = sizeButton.getAttribute("data-qa-action");
        final var inStock = qaAction != null && qaAction.contains(inStockMarker);

        final var ariaDisabled = sizeButton.getAttribute("aria-disabled");
        if (TRUE.toString().equalsIgnoreCase(ariaDisabled) || !sizeButton.isEnabled()) {
            return false;
        }

        return inStock;
    }

    private String readProductName() {
        return this.webDriverWait
                .until(ExpectedConditions.visibilityOfElementLocated(productName))
                .getText()
                .trim()
                .replaceAll("\\s+", " ");
    }
}
