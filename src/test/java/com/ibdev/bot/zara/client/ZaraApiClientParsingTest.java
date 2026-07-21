package com.ibdev.bot.zara.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Offline coverage of the primary (JSON API) parse path against a saved real Zara response
 * (fixtures/zara-products-details.json — the "100% EXTRA SOFT WOOL JUMPER", fully sold out). The live
 * {@link ZaraApiClientTest} guards that the endpoint/structure still exists; this pins the parsing
 * logic (availability mapping, WHOLE aggregation, price exponent, full lineup) without needing network.
 *
 * @author i.bogatskii
 */
class ZaraApiClientParsingTest {

    private static final String COLOR_PRODUCT_ID = "495689401";
    private static final String LINK =
            "https://www.zara.com/me/en/100-extra-soft-wool-jumper-p09598104.html?v1=" + COLOR_PRODUCT_ID;

    private final ZaraApiClient client = new ZaraApiClient(null);

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/zara-products-details.json")) {
            assertThat(in).as("fixture present on classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ZaraApiClient.ColorMatch match() throws Exception {
        final var match = client.matchFromBody(fixture(), COLOR_PRODUCT_ID);
        assertThat(match).as("color matched by productId").isNotNull();
        return match;
    }

    @Test
    void snapshotParsesAvailabilityWholeAndPrice() throws Exception {
        final var snapshot = client.toSnapshot(match());

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.sizes()).containsOnly(
                java.util.Map.entry("XS", false),
                java.util.Map.entry("S", false),
                java.util.Map.entry("M", false),
                java.util.Map.entry("L", false),
                java.util.Map.entry("XL", false),
                java.util.Map.entry("*", false));
        assertThat(snapshot.lowStockSizes()).isEmpty();
        assertThat(snapshot.price()).isNotNull();
        assertThat(snapshot.price().amount()).isEqualTo(1198L);
        assertThat(snapshot.price().currency()).isEqualTo("EUR");
        assertThat(snapshot.price().fractionDigits()).isEqualTo(2);
    }

    @Test
    void cardParsesFullLineupNameKeyAndPrice() throws Exception {
        final var card = client.toCard(match(), LINK);

        assertThat(card).isNotNull();
        assertThat(card.getName()).isEqualTo("100% EXTRA SOFT WOOL JUMPER");
        assertThat(card.getProductKey()).isEqualTo("09598104");
        assertThat(card.getSizeDetails())
                .extracting(SizeInfo::getSize, SizeInfo::isSizeAvailability)
                .containsExactly(
                        tuple("XS", false),
                        tuple("S", false),
                        tuple("M", false),
                        tuple("L", false),
                        tuple("XL", false));
        assertThat(card.getPrice().amount()).isEqualTo(1198L);
        assertThat(card.getPrice().currency()).isEqualTo("EUR");
        assertThat(card.getPrice().fractionDigits()).isEqualTo(2);
    }
}
