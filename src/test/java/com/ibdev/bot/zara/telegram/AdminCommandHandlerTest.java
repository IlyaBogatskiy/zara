package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.ProductRef;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.Watch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.WATCH_IN_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminCommandHandlerTest {

    private static final long ADMIN = 999L;
    private static final long OTHER = 111L;

    @Mock
    private SubscriptionService subscriptionService;

    private AdminCommandHandler handler;

    @BeforeEach
    void setUp() {
        final var props = new ZaraProperties();
        props.setAdminChatId(ADMIN);
        handler = new AdminCommandHandler(subscriptionService, props);

        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of("K1", "K2")));
        when(subscriptionService.getActiveWatches("K1"))
                .thenReturn(List.of(new Watch(1L, "S", AWAIT_RESTOCK), new Watch(2L, "M", WATCH_IN_STOCK)));
        when(subscriptionService.getActiveWatches("K2"))
                .thenReturn(List.of(new Watch(1L, "L", AWAIT_RESTOCK)));
        when(subscriptionService.getSubscribersByProduct("K1"))
                .thenReturn(Map.of(1L, Set.of("S"), 2L, Set.of("M")));
        when(subscriptionService.getSubscribersByProduct("K2"))
                .thenReturn(Map.of(1L, Set.of("L")));
        when(subscriptionService.findProductRef("K1")).thenReturn(new ProductRef("https://z/k1", "Куртка"));
        when(subscriptionService.findProductRef("K2")).thenReturn(new ProductRef("https://z/k2", "Джинсы"));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(Map.of("K1", new PriceInfo(5805, "EUR", 2)));
        when(subscriptionService.getAllSubscribedSizes(1L))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("K1", Set.of("S"), "K2", Set.of("L"))));
        when(subscriptionService.getSubscribedSizeModes(1L, "K1")).thenReturn(Map.of("S", AWAIT_RESTOCK));
        when(subscriptionService.getSubscribedSizeModes(1L, "K2")).thenReturn(Map.of("L", AWAIT_RESTOCK));
    }

    @Test
    void ignoresNonAdminChatEvenForAKnownCommand() {
        assertThat(handler.tryHandle(OTHER, "/stats")).isEmpty();
    }

    @Test
    void ignoresNonCommandTextFromAdminSoNormalFlowStillWorks() {
        assertThat(handler.tryHandle(ADMIN, "https://www.zara.com/x-p1.html")).isEmpty();
    }

    @Test
    void ignoresUnknownCommand() {
        assertThat(handler.tryHandle(ADMIN, "/whatever")).isEmpty();
    }

    @Test
    void statsReportsProductChatAndModeCounts() {
        final var reply = handler.tryHandle(ADMIN, "/stats").orElseThrow();

        assertThat(reply).contains(
                "Товаров в мониторинге: 2",
                "Уникальных чатов: 2",
                "Подписок на размеры: 3",
                "ждут рестока: 2",
                "следят: 1");
    }

    @Test
    void productsListsEveryProductWithSubscriberCount() {
        final var reply = handler.tryHandle(ADMIN, "/products").orElseThrow();

        assertThat(reply).contains("K1", "Куртка", "K2", "Джинсы");
    }

    @Test
    void productDetailsShowsWatchersSizesModesAndPrice() {
        final var reply = handler.tryHandle(ADMIN, "/product K1").orElseThrow();

        assertThat(reply).contains("Куртка", "чат 1", "S(ждёт)", "чат 2", "M(следит)", "58.05");
    }

    @Test
    void chatsListsChatsWithProductCounts() {
        final var reply = handler.tryHandle(ADMIN, "/chats").orElseThrow();

        assertThat(reply).contains("чат 1", "2 товар", "чат 2", "1 товар");
    }

    @Test
    void chatDetailsShowsWhatAChatMonitors() {
        final var reply = handler.tryHandle(ADMIN, "/chat 1").orElseThrow();

        assertThat(reply).contains("Куртка", "S(ждёт)", "Джинсы", "L(ждёт)");
    }

    @Test
    void adminCommandPrintsHelp() {
        final var reply = handler.tryHandle(ADMIN, "/admin").orElseThrow();

        assertThat(reply).contains("Админ-команды", "/stats", "/product", "/chat");
    }
}
