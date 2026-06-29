package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.client.ProductSnapshot;
import com.ibdev.bot.zara.notify.UserNotifier;
import com.ibdev.bot.zara.service.page.PageService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.Watch;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.AUTO_AVAILABLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.WATCH_IN_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringSchedulerTest {

    private static final String KEY = "03992419";
    private static final String LINK = "https://www.zara.com/me/en/test-p03992419.html?v1=1";
    private static final SubscriptionService.ProductRef REF =
            new SubscriptionService.ProductRef(LINK, "Test product");

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private PageService pageService;

    @Mock
    private TelegramBot telegramBot;

    private MonitoringScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MonitoringScheduler(subscriptionService, pageService, new UserNotifier(telegramBot));
    }

    private static Watch watch(final long chatId, final String size, final com.ibdev.bot.zara.storage.model.SubscriptionMode mode) {
        return new Watch(chatId, size, mode);
    }

    private static PriceInfo eur(final long amount) {
        return new PriceInfo(amount, "EUR", 2);
    }

    private void stubProduct(final List<Watch> watches, final Map<String, Boolean> sizes, final PriceInfo price) {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(pageService.checkProductSizesAvailability(LINK)).thenReturn(new ProductSnapshot(sizes, price));
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(watches);
    }

    private void seed(final Map<String, Boolean> sizes) {
        seed(sizes, Map.of());
    }

    private void seed(final Map<String, Boolean> sizes, final Map<String, PriceInfo> prices) {
        when(subscriptionService.loadLastKnown()).thenReturn(Map.of(KEY, sizes));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(prices);
        scheduler.seedLastKnown();
    }

    private List<SendMessage> sentMessages() {
        final var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    private String textOf(final SendMessage message) {
        return (String) message.getParameters().get("text");
    }

    @Test
    void asksToKeepWatchingWhenSizeAppearsAndDoesNotUnsubscribe() {
        seed(Map.of("S", false));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", true, "*", true), null);

        scheduler.monitor();

        final var message = sentMessages().getFirst();
        assertThat(textOf(message)).contains("Размер S", "Test product", "появился");
        assertThat(message.getParameters().get("reply_markup")).isNotNull();
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
        verify(subscriptionService).recordCheck(KEY, Map.of("S", true, "*", true));
    }

    @Test
    void staysSilentWhileSizeIsStillOutOfStock() {
        seed(Map.of("S", false));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", false, "*", false), null);

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
        verify(subscriptionService).recordCheck(KEY, Map.of("S", false, "*", false));
    }

    @Test
    void noDuplicateNotificationAfterRestartWhenAlreadyInStock() {
        seed(Map.of("S", true));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", true, "*", true), null);

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
    }

    @Test
    void absentHistoryIsTreatedAsOutOfStock() {
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", true, "*", true), null);

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
    }

    @Test
    void matchesSizesFuzzily() {
        seed(Map.of("40", false));
        stubProduct(List.of(watch(1L, "40", AWAIT_RESTOCK)), Map.of("EU40", true, "*", true), null);

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер 40", "появился");
    }

    @Test
    void notifiesEverySubscribedChatWithOneScrape() {
        seed(Map.of("S", false, "M", false));
        stubProduct(
                List.of(watch(1L, "S", AWAIT_RESTOCK), watch(2L, "S", AWAIT_RESTOCK), watch(2L, "M", AWAIT_RESTOCK)),
                Map.of("S", true, "M", false, "*", true),
                null
        );

        scheduler.monitor();

        verify(pageService, times(1)).checkProductSizesAvailability(LINK);
        final var messages = sentMessages();
        assertThat(messages).hasSize(2);
        assertThat(messages).allSatisfy(m -> assertThat(textOf(m)).contains("Размер S"));
    }

    @Test
    void notifiesPriceDropForInStockWatcher() {
        seed(Map.of("S", true), Map.of(KEY, eur(2995)));
        stubProduct(List.of(watch(1L, "S", WATCH_IN_STOCK)), Map.of("S", true, "*", true), eur(1797));

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("снизилась", "29.95 EUR", "17.97 EUR");
        verify(subscriptionService).recordPrice(KEY, eur(1797));
    }

    @Test
    void noPriceNotificationWithoutABaseline() {
        seed(Map.of("S", true));
        stubProduct(List.of(watch(1L, "S", WATCH_IN_STOCK)), Map.of("S", true, "*", true), eur(1797));

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(subscriptionService).recordPrice(KEY, eur(1797));
    }

    @Test
    void priceMoveDoesNotNotifyAwaitRestockWatcher() {
        seed(Map.of("S", false), Map.of(KEY, eur(2995)));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", false, "*", false), eur(1797));

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(subscriptionService).recordPrice(KEY, eur(1797));
    }

    @Test
    void asksToKeepWaitingWhenWatchedSizeSellsOut() {
        seed(Map.of("S", true), Map.of(KEY, eur(1797)));
        stubProduct(List.of(watch(1L, "S", WATCH_IN_STOCK)), Map.of("S", false, "*", false), eur(1797));

        scheduler.monitor();

        final var message = sentMessages().getFirst();
        assertThat(textOf(message)).contains("Размер S", "пропал");
        assertThat(message.getParameters().get("reply_markup")).isNotNull();
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
    }

    @Test
    void wholeProductWithMissingSizesOffersKeepMonitoringButton() {
        stubProduct(List.of(watch(1L, "*", AWAIT_RESTOCK)), Map.of("S", true, "M", false, "*", true), null);

        scheduler.monitor();

        final var message = sentMessages().getFirst();
        assertThat(textOf(message)).contains("Товар появился", "[M]", "отсутствующими размерами");
        assertThat(message.getParameters().get("reply_markup")).isNotNull();
        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("*"), AUTO_AVAILABLE);
    }

    @Test
    void wholeProductFullyAvailableJustStopsMonitoring() {
        stubProduct(List.of(watch(1L, "*", AWAIT_RESTOCK)), Map.of("S", true, "M", true, "*", true), null);

        scheduler.monitor();

        final var message = sentMessages().getFirst();
        assertThat(textOf(message)).contains("остановил мониторинг");
        assertThat(message.getParameters().get("reply_markup")).isNull();
        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("*"), AUTO_AVAILABLE);
    }

    @Test
    void wholeProductStillUnavailableStaysSilent() {
        stubProduct(List.of(watch(1L, "*", AWAIT_RESTOCK)), Map.of("S", false, "*", false), null);

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
    }

    @Test
    void oneFailingProductDoesNotBreakTheTick() {
        final var keys = new LinkedHashSet<>(List.of("broken", KEY));
        when(subscriptionService.activeProductKeys()).thenReturn(keys);
        when(subscriptionService.getProductRef("broken"))
                .thenReturn(new SubscriptionService.ProductRef("https://broken", "Broken"));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(pageService.checkProductSizesAvailability("https://broken"))
                .thenThrow(new RuntimeException("selenium died"));
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null));
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
    }

    @Test
    void doesNothingWithoutSubscriptions() {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of());

        scheduler.monitor();

        verifyNoInteractions(pageService, telegramBot);
    }
}
