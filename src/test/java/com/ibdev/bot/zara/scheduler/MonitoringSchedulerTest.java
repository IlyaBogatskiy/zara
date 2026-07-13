package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.client.ProductSnapshot;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.UserNotifier;
import com.ibdev.bot.zara.service.page.PageService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.Watch;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

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

    /**
     * Defaults to immediate commits and no Selenium confirmation, so the transition-logic tests stay
     * focused; debounce and confirmation get their own tests that build a tuned scheduler.
     */
    @BeforeEach
    void setUp() {
        scheduler = scheduler(1, false);
    }

    private MonitoringScheduler scheduler(final int confirmations, final boolean confirmViaSelenium) {
        final var props = new ZaraProperties();
        props.getMonitor().setConfirmations(confirmations);
        props.getMonitor().setConfirmRestockViaSelenium(confirmViaSelenium);
        return new MonitoringScheduler(subscriptionService, pageService, new UserNotifier(telegramBot), props);
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
    void foldsSeveralChangesForOneChatIntoASingleReport() {
        seed(Map.of("S", false), Map.of(KEY, eur(2995)));
        stubProduct(
                List.of(watch(1L, "S", AWAIT_RESTOCK), watch(1L, "M", WATCH_IN_STOCK)),
                Map.of("S", true, "M", true, "*", true),
                eur(1797)
        );

        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(1);
        assertThat(textOf(messages.getFirst()))
                .contains("Размер S", "появился", "снизилась", "29.95 EUR", "17.97 EUR");
    }

    @Test
    void foldsChangesAcrossProductsIntoOneReportPerChat() {
        final var keyB = "07654321";
        final var linkB = "https://www.zara.com/me/en/b-p07654321.html?v1=1";
        final var refB = new SubscriptionService.ProductRef(linkB, "Product B");

        when(subscriptionService.loadLastKnown())
                .thenReturn(Map.of(KEY, Map.of("S", false), keyB, Map.of("M", false)));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(Map.of());
        scheduler.seedLastKnown();

        when(subscriptionService.activeProductKeys())
                .thenReturn(new LinkedHashSet<>(List.of(KEY, keyB)));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getProductRef(keyB)).thenReturn(refB);
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null));
        when(pageService.checkProductSizesAvailability(linkB))
                .thenReturn(new ProductSnapshot(Map.of("M", true, "*", true), null));
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(subscriptionService.getActiveWatches(keyB)).thenReturn(List.of(watch(1L, "M", AWAIT_RESTOCK)));

        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(1);
        assertThat(textOf(messages.getFirst()))
                .contains("Test product", "Размер S", "Product B", "Размер M");
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
    void debounceHoldsARestockUntilItIsSeenTwiceInARow() {
        scheduler = scheduler(2, false);
        seed(Map.of("S", false));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", true, "*", true), null);

        scheduler.monitor();
        verify(telegramBot, never()).execute(any(SendMessage.class));

        scheduler.monitor();
        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
    }

    /**
     * A blip in-stock for one tick, then back out, must never notify.
     */
    @Test
    void debounceIgnoresASingleTickBlip() {
        scheduler = scheduler(2, false);
        seed(Map.of("S", false));
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null))
                .thenReturn(new ProductSnapshot(Map.of("S", false, "*", false), null));

        scheduler.monitor();
        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
    }

    /**
     * The API reports in-stock, but the real page is the "unavailable" one (only WHOLE=false), so
     * the Selenium cross-check must suppress the false restock alert.
     */
    @Test
    void suppressesRestockWhenSeleniumSaysProductUnavailable() {
        scheduler = scheduler(1, true);
        seed(Map.of("S", false));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", true, "*", true), null);
        when(pageService.checkViaSelenium(LINK)).thenReturn(new ProductSnapshot(Map.of("*", false), null));

        scheduler.monitor();

        verify(pageService).checkViaSelenium(LINK);
        verify(telegramBot, never()).execute(any(SendMessage.class));
    }

    @Test
    void notifiesRestockWhenSeleniumConfirmsAvailability() {
        scheduler = scheduler(1, true);
        seed(Map.of("S", false));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", true, "*", true), null);
        when(pageService.checkViaSelenium(LINK)).thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null));

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
    }

    @Test
    void marksLowStockInTheAppearedNotification() {
        seed(Map.of("S", false));
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null, Set.of("S")));

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился", "Мало осталось");
    }

    /**
     * The audit line ties together WHAT (sold-out), WHO (chat 1), WHICH product+size, and WHY
     * (confirmed transition) — enough to reconstruct why the alert fired at this moment.
     */
    @Test
    void logsTheCauseOfEachAlertForPostMortemAnalysis() {
        seed(Map.of("S", true), Map.of(KEY, eur(1797)));
        stubProduct(List.of(watch(1L, "S", WATCH_IN_STOCK)), Map.of("S", false, "*", false), eur(1797));

        final var logs = captureMonitoringLogs(() -> scheduler.monitor());

        assertThat(logs).anySatisfy(line -> assertThat(line)
                .contains("SIZE_SOLD_OUT", KEY, "chat 1", "'S'"));
    }

    private List<String> captureMonitoringLogs(final Runnable action) {
        final var logbackLogger = (Logger) LoggerFactory.getLogger(MonitoringScheduler.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        final var previousLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.INFO);
        logbackLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(previousLevel);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Regression guard: enabling it by default put a synchronous Selenium scrape on the
     * single-threaded scheduler, which delayed and suppressed alerts. It must stay opt-in.
     */
    @Test
    void seleniumRestockConfirmationIsOffByDefault() {
        assertThat(new ZaraProperties().getMonitor().isConfirmRestockViaSelenium()).isFalse();
    }

    @Test
    void doesNothingWithoutSubscriptions() {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of());

        scheduler.monitor();

        verifyNoInteractions(pageService, telegramBot);
    }
}
