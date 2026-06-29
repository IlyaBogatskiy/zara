package com.ibdev.bot.zara.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.util.ProductLinks.extractProductId;

/**
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ZaraApiClient {

    /**
     * Store region/language from the link path, e.g. "/me/en/".
     */
    private static final Pattern STORE_PATH = Pattern.compile("^(/[a-z]{2}/[a-z]{2}/)");

    /**
     * low_on_stock still means the item can be bought.
     */
    private static final Set<String> IN_STOCK_VALUES = Set.of("in_stock", "low_on_stock");

    private final RestClient zaraRestClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * Live snapshot: size availability (size → inStock, plus WHOLE "*" → whether any
     * size is in stock) and the current price. The contract matches
     * ZaraPageClient.checkSizesAvailability.
     */
    public ProductSnapshot checkSizesAvailability(final String link) {
        final var match = findColor(link);
        if (match == null) {
            return null;
        }

        final var sizes = match.color().path("sizes");
        if (!sizes.isArray() || sizes.isEmpty()) {
            return null;
        }

        final var state = new LinkedHashMap<String, Boolean>();
        var anyInStock = false;

        for (final JsonNode size : sizes) {
            final var name = sizeName(size);
            if (name == null) {
                continue;
            }

            final var inStock = isInStock(size);
            state.put(name, inStock);
            anyInStock |= inStock;
        }

        if (state.isEmpty()) {
            return null;
        }

        state.put(WHOLE.getSize(), anyInStock);
        return new ProductSnapshot(state, parsePrice(match.color()));
    }

    /**
     * Product card. The convention matches ZaraPageClient.loadProductCard:
     * sizeDetails contains only the sizes that are OUT of stock (sizeAvailability=false).
     */
    public ProductCard loadProductCard(final String link) {
        final var match = findColor(link);
        if (match == null) {
            return null;
        }

        final var sizes = match.color().path("sizes");
        if (!sizes.isArray() || sizes.isEmpty()) {
            return null;
        }

        final var outOfStock = new ArrayList<SizeInfo>();
        for (final JsonNode size : sizes) {
            final var name = sizeName(size);
            if (name != null && !isInStock(size)) {
                outOfStock.add(new SizeInfo(name, false));
            }
        }

        final var dto = new ProductCard();
        dto.setProductKey(extractProductId(link));
        dto.setLink(link);
        dto.setName(match.productName());
        dto.setSizeDetails(outOfStock);
        dto.setPrice(parsePrice(match.color()));
        return dto;
    }

    /**
     * Current price from the color node: price is already in minor units,
     * the currency/exponent live under pricing.price.currency. Returns null
     * if the price is missing — price tracking is best-effort, never fatal.
     */
    private PriceInfo parsePrice(final JsonNode color) {
        final var amount = color.path("price");
        if (!amount.isNumber()) {
            return null;
        }

        final var currency = color.path("pricing").path("price").path("currency");
        final var code = currency.path("code").asString();
        final var exponent = currency.path("exponent").asInt(-2);

        return new PriceInfo(amount.asLong(), (code == null || code.isBlank()) ? null : code, -exponent);
    }

    private record ColorMatch(String productName, JsonNode color) {
    }

    private ColorMatch findColor(final String link) {
        final var uri = URI.create(link);
        final var colorProductId = queryParam(uri, "v1");
        final var storePath = storePath(uri);
        if (colorProductId == null || storePath == null) {
            log.info("Link {} has no v1/store path, Zara API not applicable.", link);
            return null;
        }

        final var endpoint = "https://www.zara.com" + storePath
                + "products-details?productIds=" + colorProductId + "&ajax=true";

        final var body = fetchWithRetry(endpoint);

        final var root = this.jsonMapper.readTree(body);
        if (root == null || !root.isArray() || root.isEmpty()) {
            log.info("Zara API returned no products for productId {}.", colorProductId);
            return null;
        }

        for (final JsonNode product : root) {
            for (final JsonNode color : product.path("detail").path("colors")) {
                if (colorProductId.equals(color.path("productId").asString())) {
                    return new ColorMatch(product.path("name").asString(), color);
                }
            }
        }

        log.info("Zara API response has no color with productId {}.", colorProductId);
        return null;
    }

    /**
     * A single transient network failure must not drop the request into the heavy Selenium fallback.
     */
    private String fetchWithRetry(final String endpoint) {
        RestClientException lastFailure = null;

        for (var attempt = 1; attempt <= 2; attempt++) {
            try {
                return this.zaraRestClient.get()
                        .uri(endpoint)
                        .retrieve()
                        .body(String.class);
            } catch (final RestClientException e) {
                lastFailure = e;
                log.info("Zara API attempt {} failed: {}", attempt, e.getMessage());

                try {
                    Thread.sleep(300);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        throw lastFailure;
    }

    private String sizeName(final JsonNode size) {
        final var name = size.path("name").asString();
        return (name == null || name.isBlank()) ? null : name.trim();
    }

    private boolean isInStock(final JsonNode size) {
        return IN_STOCK_VALUES.contains(size.path("availability").asString());
    }

    private String queryParam(final URI uri, final String param) {
        final var query = uri.getQuery();
        if (query == null) {
            return null;
        }

        for (final var pair : query.split("&")) {
            final var idx = pair.indexOf('=');
            if (idx > 0 && param.equals(pair.substring(0, idx))) {
                final var value = pair.substring(idx + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private String storePath(final URI uri) {
        final var path = uri.getPath();
        if (path == null) {
            return null;
        }

        final var matcher = STORE_PATH.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }
}
