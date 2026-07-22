package com.ibdev.bot.zara.client;

import com.ibdev.bot.zara.config.ZaraApiConfig;
import com.ibdev.bot.zara.config.ZaraProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author i.bogatskii
 */
class ZaraApiClientTest {

    private final ZaraProperties properties = new ZaraProperties();
    private final ZaraApiClient client = new ZaraApiClient(
            new ZaraApiConfig().zaraRestClient(properties), properties
    );

    @Test
    void checksAvailabilityViaLiveApi() {
        final var snapshot = client.checkSizesAvailability(
                "https://www.zara.com/me/en/slogan-print-pocket-t-shirt-p03992419.html?v1=500559589&v2=2432042"
        );

        assertNotNull(snapshot, "API должен вернуть снапшот товара");
        final var state = snapshot.sizes();
        assertNotNull(state, "API должен вернуть состояние размеров");
        assertTrue(state.containsKey(WHOLE.getSize()), "должен присутствовать агрегат WHOLE (*)");
        assertTrue(state.size() > 1, "должен быть хотя бы один реальный размер помимо WHOLE");
        state.forEach((size, inStock) -> assertNotNull(inStock));

        assertNotNull(snapshot.price(), "API должен вернуть цену");
        assertTrue(snapshot.price().amount() > 0, "цена должна быть положительной");
    }

    @Test
    void loadsFullSizeLineupViaLiveApi() {
        final var link = "https://www.zara.com/me/en/slogan-print-pocket-t-shirt-p03992419.html?v1=500559589&v2=2432042";
        final var card = client.loadProductCard(link);

        assertNotNull(card, "API должен вернуть карточку");
        assertEquals("03992419", card.getProductKey());
        assertNotNull(card.getName());
        assertFalse(card.getName().isBlank());
        assertNotNull(card.getSizeDetails(), "линейка размеров не должна быть null");
        assertFalse(card.getSizeDetails().isEmpty(), "карточка содержит полную линейку размеров");

        final var snapshot = client.checkSizesAvailability(link);
        final var expected = new HashMap<>(snapshot.sizes());
        expected.remove(WHOLE.getSize());

        final var actual = new HashMap<String, Boolean>();
        card.getSizeDetails().forEach(s -> actual.put(s.getSize(), s.isSizeAvailability()));

        assertEquals(expected, actual, "линейка карточки совпадает с наличием (размеры в наличии тоже включены)");
    }

    @Test
    void returnsNullWhenLinkHasNoColorProductId() {
        assertNull(client.checkSizesAvailability(
                "https://www.zara.com/me/en/slogan-print-pocket-t-shirt-p03992419.html"
        ));
    }

    @Test
    void returnsNullForUnknownProductId() {
        assertNull(client.checkSizesAvailability(
                "https://www.zara.com/me/en/whatever-p00000000.html?v1=999999999"
        ));
    }
}
