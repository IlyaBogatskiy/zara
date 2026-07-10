package com.ibdev.bot.zara.service.subscription;

import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.SizeInfo;
import com.ibdev.bot.zara.storage.repo.ProductRepository;
import com.ibdev.bot.zara.storage.repo.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.storage.model.SubscriptionMode;

import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.AUTO_AVAILABLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.USER_ACTION;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.WATCH_IN_STOCK;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author i.bogatskii
 */
@DataJpaTest(
        showSql = false,
        properties = {
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "logging.level.org.hibernate.SQL=warn",
                "logging.level.org.hibernate.orm.jdbc.bind=warn"
        }
)
@Import(SubscriptionService.class)
class SubscriptionServiceIntegrationTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ProductRepository productRepository;

    private ProductCard card(final String productKey) {
        return new ProductCard(
                productKey,
                "Test product " + productKey,
                "https://www.zara.com/me/en/test-p" + productKey + ".html?v1=1",
                List.of(),
                null
        );
    }

    @Test
    void subscribeWritesThroughToDatabaseAndIndexes() {
        subscriptionService.subscribe(101L, card("p101"), Set.of("S", "M"));

        assertThat(productRepository.findById("p101")).isPresent();
        final var rows = subscriptionRepository.findByProductKeyAndClosedAtIsNull("p101");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getChatId()).isEqualTo(101L);
            assertThat(row.getLastKnownInStock()).isFalse();
            assertThat(row.getCreatedAt()).isNotNull();
        });

        assertThat(subscriptionService.getSubscribedSizes(101L, "p101")).containsExactlyInAnyOrder("S", "M");
        assertThat(subscriptionService.activeProductKeys()).contains("p101");
        assertThat(subscriptionService.getProductRef("p101").name()).isEqualTo("Test product p101");
    }

    @Test
    void subscribeIsIdempotentForSameSizes() {
        subscriptionService.subscribe(102L, card("p102"), Set.of("S"));
        subscriptionService.subscribe(102L, card("p102"), Set.of("S"));

        assertThat(subscriptionRepository.findByProductKeyAndClosedAtIsNull("p102")).hasSize(1);
        assertThat(subscriptionService.getSubscribedSizes(102L, "p102")).containsExactly("S");
    }

    @Test
    void subscribeWithEmptySizesIsNoOp() {
        subscriptionService.subscribe(103L, card("p103"), Set.of());

        assertThat(productRepository.findById("p103")).isEmpty();
        assertThat(subscriptionService.getAllSubscribedSizes(103L)).isEmpty();
    }

    @Test
    void unsubscribeClosesRowWithReasonAndKeepsHistory() {
        subscriptionService.subscribe(104L, card("p104"), Set.of("S", "M"));
        subscriptionService.unsubscribe(104L, "p104", Set.of("S"), USER_ACTION);

        final var closed = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(104L, "p104", "S");
        assertThat(closed).isNotNull();
        assertThat(closed.isActive()).isFalse();
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getCloseReason()).isEqualTo(USER_ACTION);

        assertThat(subscriptionService.getSubscribedSizes(104L, "p104")).containsExactly("M");
    }

    @Test
    void resubscribeReopensClosedRow() {
        subscriptionService.subscribe(105L, card("p105"), Set.of("S"));
        subscriptionService.unsubscribe(105L, "p105", Set.of("S"), AUTO_AVAILABLE);
        subscriptionService.subscribe(105L, card("p105"), Set.of("S"));

        assertThat(subscriptionRepository.findAll()
                .stream().filter(s -> "p105".equals(s.getProductKey())).toList()).hasSize(1);

        final var reopened = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(105L, "p105", "S");
        assertThat(reopened.isActive()).isTrue();
        assertThat(reopened.getCloseReason()).isNull();
        assertThat(reopened.getLastKnownInStock()).isFalse();
        assertThat(subscriptionService.getSubscribedSizes(105L, "p105")).containsExactly("S");
    }

    @Test
    void unsubscribeAllClosesEverythingAndCleansIndexes() {
        subscriptionService.subscribe(106L, card("p106"), Set.of("S", "M", "L"));
        subscriptionService.unsubscribeAll(106L, "p106", USER_ACTION);

        assertThat(subscriptionRepository.findByProductKeyAndClosedAtIsNull("p106")).isEmpty();
        assertThat(subscriptionService.getAllSubscribedSizes(106L)).isEmpty();
        assertThat(subscriptionService.activeProductKeys()).doesNotContain("p106");
        assertThat(subscriptionService.getProductRef("p106")).isNull();
    }

    @Test
    void reverseIndexTracksMultipleChats() {
        subscriptionService.subscribe(107L, card("p107"), Set.of("S"));
        subscriptionService.subscribe(108L, card("p107"), Set.of("M"));

        final var subscribers = subscriptionService.getSubscribersByProduct("p107");
        assertThat(subscribers).containsOnlyKeys(107L, 108L);
        assertThat(subscribers.get(107L)).containsExactly("S");
        assertThat(subscribers.get(108L)).containsExactly("M");

        subscriptionService.unsubscribeAll(107L, "p107", USER_ACTION);
        assertThat(subscriptionService.activeProductKeys()).contains("p107");
        assertThat(subscriptionService.getProductRef("p107")).isNotNull();

        subscriptionService.unsubscribeAll(108L, "p107", USER_ACTION);
        assertThat(subscriptionService.activeProductKeys()).doesNotContain("p107");
    }

    @Test
    void recordCheckPersistsLastKnownAvailabilityWithFuzzySizeMatch() {
        subscriptionService.subscribe(109L, card("p109"), Set.of("40", "S"));

        subscriptionService.recordCheck("p109", Map.of("EU40", true, "S", false, "*", true));

        final var size40 = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(109L, "p109", "40");
        assertThat(size40.getLastKnownInStock()).isTrue();
        assertThat(size40.getLastCheckedAt()).isNotNull();

        final var sizeS = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(109L, "p109", "S");
        assertThat(sizeS.getLastKnownInStock()).isFalse();

        assertThat(subscriptionService.loadLastKnown())
                .containsEntry("p109", Map.of("40", true, "S", false));
    }

    @Test
    void warmUpRestoresIndexesAfterRestart() {
        subscriptionService.subscribe(110L, card("p110"), Set.of("S", "M"));
        subscriptionService.recordCheck("p110", Map.of("S", true, "M", false));

        final var restarted = new SubscriptionService(productRepository, subscriptionRepository);
        restarted.warmUp();

        assertThat(restarted.getSubscribedSizes(110L, "p110")).containsExactlyInAnyOrder("S", "M");
        assertThat(restarted.activeProductKeys()).contains("p110");
        assertThat(restarted.getProductRef("p110").link()).contains("p110");
        assertThat(restarted.loadLastKnown()).containsEntry("p110", Map.of("S", true, "M", false));
    }

    @Test
    void watchInStockSwitchesModeAndShowsUpInActiveWatches() {
        subscriptionService.subscribe(120L, card("p120"), Set.of("S"));

        final var switched = subscriptionService.watchInStock(120L, "p120", "S");
        assertThat(switched).isTrue();

        final var row = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(120L, "p120", "S");
        assertThat(row.getMode()).isEqualTo(WATCH_IN_STOCK);
        assertThat(row.getLastKnownInStock()).isTrue();

        assertThat(subscriptionService.getActiveWatches("p120"))
                .containsExactly(new SubscriptionService.Watch(120L, "S", WATCH_IN_STOCK));
    }

    @Test
    void awaitRestockSwitchesModeBack() {
        subscriptionService.subscribe(121L, card("p121"), Set.of("M"));
        subscriptionService.watchInStock(121L, "p121", "M");

        final var switched = subscriptionService.awaitRestock(121L, "p121", "M");
        assertThat(switched).isTrue();

        final var row = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(121L, "p121", "M");
        assertThat(row.getMode()).isEqualTo(AWAIT_RESTOCK);
        assertThat(row.getLastKnownInStock()).isFalse();
    }

    @Test
    void watchInStockReturnsFalseForUnknownSubscription() {
        assertThat(subscriptionService.watchInStock(122L, "p122", "S")).isFalse();
    }

    @Test
    void recordPricePersistsAndReloadsBaseline() {
        subscriptionService.subscribe(123L, card("p123"), Set.of("S"));

        subscriptionService.recordPrice("p123", new PriceInfo(1797, "EUR", 2));

        final var product = productRepository.findById("p123").orElseThrow();
        assertThat(product.getLastPriceAmount()).isEqualTo(1797);
        assertThat(product.getLastPriceCurrency()).isEqualTo("EUR");
        assertThat(product.getLastPriceFractionDigits()).isEqualTo(2);

        assertThat(subscriptionService.loadLastKnownPrices())
                .containsEntry("p123", new PriceInfo(1797, "EUR", 2));
    }

    @Test
    void newSubscriptionDefaultsToAwaitRestock() {
        subscriptionService.subscribe(124L, card("p124"), Set.of("S"));

        assertThat(subscriptionService.getActiveWatches("p124"))
                .containsExactly(new SubscriptionService.Watch(124L, "S", SubscriptionMode.AWAIT_RESTOCK));
    }

    @Test
    void subscribeAssignsModeByCurrentAvailability() {
        final var card = new ProductCard(
                "p125", "Test product p125",
                "https://www.zara.com/me/en/test-pp125.html?v1=1",
                List.of(new SizeInfo("S", false), new SizeInfo("M", true)),
                null
        );

        subscriptionService.subscribe(125L, card, Set.of("S", "M"));

        final var s = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(125L, "p125", "S");
        assertThat(s.getMode()).isEqualTo(AWAIT_RESTOCK);
        assertThat(s.getLastKnownInStock()).isFalse();

        final var m = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(125L, "p125", "M");
        assertThat(m.getMode()).isEqualTo(WATCH_IN_STOCK);
        assertThat(m.getLastKnownInStock()).isTrue();

        assertThat(subscriptionService.getActiveWatches("p125")).containsExactlyInAnyOrder(
                new SubscriptionService.Watch(125L, "S", AWAIT_RESTOCK),
                new SubscriptionService.Watch(125L, "M", WATCH_IN_STOCK)
        );
    }

    @Test
    void subscribeMatchesAvailabilityFuzzily() {
        final var card = new ProductCard(
                "p126", "Test product p126",
                "https://www.zara.com/me/en/test-pp126.html?v1=1",
                List.of(new SizeInfo("EU40", true)),
                null
        );

        subscriptionService.subscribe(126L, card, Set.of("40"));

        final var row = subscriptionRepository.findByChatIdAndProductKeyAndSizeLabel(126L, "p126", "40");
        assertThat(row.getMode()).isEqualTo(WATCH_IN_STOCK);
        assertThat(row.getLastKnownInStock()).isTrue();
    }

    @Test
    void findProductRefFallsBackToDatabaseAfterLastUnsubscribe() {
        subscriptionService.subscribe(111L, card("p111"), Set.of("*"));
        subscriptionService.unsubscribe(111L, "p111", Set.of("*"), AUTO_AVAILABLE);

        assertThat(subscriptionService.getProductRef("p111")).isNull();
        final var ref = subscriptionService.findProductRef("p111");
        assertThat(ref).isNotNull();
        assertThat(ref.link()).contains("p111");
        assertThat(ref.name()).isEqualTo("Test product p111");
    }
}
