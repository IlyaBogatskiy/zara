package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.client.ProductSnapshot;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.AdminNotifier;
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
import org.mockito.stubbing.OngoingStubbing;
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

    @Mock
    private AdminNotifier adminNotifier;

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
        props.getMonitor().setBurstConfirm(false);
        return new MonitoringScheduler(subscriptionService, pageService, new UserNotifier(telegramBot), props, adminNotifier);
    }

    private MonitoringScheduler burstScheduler(final int confirmations, final int maxPerTick) {
        final var props = new ZaraProperties();
        props.getMonitor().setConfirmations(confirmations);
        props.getMonitor().setConfirmRestockViaSelenium(false);
        props.getMonitor().setBurstConfirm(true);
        props.getMonitor().setBurstConfirmDelayMs(0);
        props.getMonitor().setBurstConfirmMaxPerTick(maxPerTick);
        return new MonitoringScheduler(subscriptionService, pageService, new UserNotifier(telegramBot), props, adminNotifier);
    }

    private MonitoringScheduler antiFlapScheduler(
            final int confirmations, final int cooldownTicks, final int quarantineTicks) {
        final var props = new ZaraProperties();
        props.getMonitor().setConfirmations(confirmations);
        props.getMonitor().setConfirmRestockViaSelenium(false);
        props.getMonitor().setBurstConfirm(true);
        props.getMonitor().setBurstConfirmDelayMs(0);
        props.getMonitor().setBurstConfirmMaxPerTick(3);
        props.getMonitor().setAntiFlapCooldownTicks(cooldownTicks);
        props.getMonitor().setFlapQuarantineTicks(quarantineTicks);
        return new MonitoringScheduler(subscriptionService, pageService, new UserNotifier(telegramBot), props, adminNotifier);
    }

    private MonitoringScheduler watchdogScheduler(final long stallAlertMs, final long slowTickAlertMs) {
        final var props = new ZaraProperties();
        props.getMonitor().setConfirmations(1);
        props.getMonitor().setBurstConfirm(false);
        props.getMonitor().setStallAlertMs(stallAlertMs);
        props.getMonitor().setSlowTickAlertMs(slowTickAlertMs);
        return new MonitoringScheduler(subscriptionService, pageService, new UserNotifier(telegramBot), props, adminNotifier);
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

    /**
     * Stubs one product whose scrape returns the given snapshots on successive ticks — the harness
     * for multi-tick lifecycle scenarios (appear → sell out → appear, several sizes at once, etc.).
     */
    private void stubProductSequence(final List<Watch> watches, final ProductSnapshot... snapshots) {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(watches);
        OngoingStubbing<ProductSnapshot> stub = when(pageService.checkProductSizesAvailability(LINK));
        for (final var snapshot : snapshots) {
            stub = stub.thenReturn(snapshot);
        }
    }

    private static ProductSnapshot snap(final Map<String, Boolean> sizes) {
        return new ProductSnapshot(sizes, null);
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

    /**
     * Sold-out must notify even for an AWAIT_RESTOCK size — availability is tracked in both
     * directions regardless of mode (mode only gates the extra price alerts).
     */
    @Test
    void notifiesSoldOutEvenForAwaitRestockSize() {
        seed(Map.of("S", true));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", false, "*", false), null);

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "пропал");
    }

    /**
     * Appeared must notify even for a WATCH_IN_STOCK size — e.g. a watched size that sold out and
     * came back is reported on its return, not just on the sell-out.
     */
    @Test
    void notifiesAppearedEvenForWatchInStockSize() {
        seed(Map.of("S", false));
        stubProduct(List.of(watch(1L, "S", WATCH_IN_STOCK)), Map.of("S", true, "*", true), null);

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
    }

    /**
     * A monitored size that sells out and then comes back must alert on BOTH transitions, in order.
     */
    @Test
    void notifiesOnSellOutThenRestockOfSingleSize() {
        seed(Map.of("S", true));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", true, "*", true)));

        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(2);
        assertThat(textOf(messages.get(0))).contains("Размер S", "пропал");
        assertThat(textOf(messages.get(1))).contains("Размер S", "появился");
    }

    /**
     * Full lifecycle of one size on one product: appeared → sold out → appeared, one alert per tick.
     */
    @Test
    void notifiesEveryTransitionOverAppearSellOutAppearLifecycle() {
        seed(Map.of("S", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", true, "*", true)));

        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(3);
        assertThat(textOf(messages.get(0))).contains("Размер S", "появился");
        assertThat(textOf(messages.get(1))).contains("Размер S", "пропал");
        assertThat(textOf(messages.get(2))).contains("Размер S", "появился");
    }

    /**
     * Two sizes of one product appearing, selling out, and reappearing together fold into ONE
     * consolidated report per tick — not one message per size.
     */
    @Test
    void twoSizesAppearingAndSellingOutTogetherFoldIntoOneReportPerTick() {
        seed(Map.of("S", false, "M", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK), watch(1L, "M", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "M", true, "*", true)),
                snap(Map.of("S", false, "M", false, "*", false)),
                snap(Map.of("S", true, "M", true, "*", true)));

        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(3);
        assertThat(textOf(messages.get(0))).contains("Размер S", "Размер M", "появился");
        assertThat(textOf(messages.get(1))).contains("Размер S", "Размер M", "пропал");
        assertThat(textOf(messages.get(2))).contains("Размер S", "Размер M", "появился");
    }

    /**
     * The L/XL case: one size appears while another sells out on the same product in the same tick —
     * both changes must arrive in a single combined report.
     */
    @Test
    void oneSizeAppearingWhileAnotherSellsOutFoldsIntoOneReport() {
        seed(Map.of("L", true, "XL", false));
        stubProductSequence(List.of(watch(1L, "L", AWAIT_RESTOCK), watch(1L, "XL", AWAIT_RESTOCK)),
                snap(Map.of("L", false, "XL", true, "*", true)));

        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(1);
        final var text = textOf(messages.getFirst());
        assertThat(text).contains("Размер XL", "появился");
        assertThat(text).contains("Размер L", "пропал");
    }

    /**
     * Three sizes going through appeared → sold out → appeared together — each tick is one report
     * carrying all three, proving consolidation scales past two sizes.
     */
    @Test
    void threeSizesLifecycleFoldEachTickIntoOneReport() {
        seed(Map.of("S", false, "M", false, "L", false));
        stubProductSequence(
                List.of(watch(1L, "S", AWAIT_RESTOCK), watch(1L, "M", AWAIT_RESTOCK), watch(1L, "L", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "M", true, "L", true, "*", true)),
                snap(Map.of("S", false, "M", false, "L", false, "*", false)),
                snap(Map.of("S", true, "M", true, "L", true, "*", true)));

        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(3);
        assertThat(textOf(messages.get(0))).contains("Размер S", "Размер M", "Размер L", "появился");
        assertThat(textOf(messages.get(1))).contains("Размер S", "Размер M", "Размер L", "пропал");
        assertThat(textOf(messages.get(2))).contains("Размер S", "Размер M", "Размер L", "появился");
    }

    /**
     * Several products, each with several sizes, all changing in one tick — the chat still gets a
     * single report grouped by product (a block per product, every size), one scrape per product.
     */
    @Test
    void severalProductsEachWithSeveralSizesFoldIntoOneReportPerChat() {
        final var keyB = "07654321";
        final var linkB = "https://www.zara.com/me/en/b-p07654321.html?v1=1";
        final var refB = new SubscriptionService.ProductRef(linkB, "Product B");

        when(subscriptionService.loadLastKnown()).thenReturn(Map.of(
                KEY, Map.of("S", false, "M", false),
                keyB, Map.of("L", false, "XL", false)));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(Map.of());
        scheduler.seedLastKnown();

        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of(KEY, keyB)));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getProductRef(keyB)).thenReturn(refB);
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "M", true, "*", true), null));
        when(pageService.checkProductSizesAvailability(linkB))
                .thenReturn(new ProductSnapshot(Map.of("L", true, "XL", true, "*", true), null));
        when(subscriptionService.getActiveWatches(KEY))
                .thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK), watch(1L, "M", AWAIT_RESTOCK)));
        when(subscriptionService.getActiveWatches(keyB))
                .thenReturn(List.of(watch(1L, "L", AWAIT_RESTOCK), watch(1L, "XL", AWAIT_RESTOCK)));

        scheduler.monitor();

        verify(pageService, times(1)).checkProductSizesAvailability(LINK);
        verify(pageService, times(1)).checkProductSizesAvailability(linkB);
        final var messages = sentMessages();
        assertThat(messages).hasSize(1);
        assertThat(textOf(messages.getFirst())).contains("Test product", "Product B",
                "Размер S", "Размер M", "Размер L", "Размер XL", "появился");
    }

    /**
     * Mirror lifecycle of one size: sold out → appeared → sold out, one alert per tick.
     */
    @Test
    void notifiesEveryTransitionOverSellOutAppearSellOutLifecycle() {
        seed(Map.of("S", true));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)));

        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(3);
        assertThat(textOf(messages.get(0))).contains("Размер S", "пропал");
        assertThat(textOf(messages.get(1))).contains("Размер S", "появился");
        assertThat(textOf(messages.get(2))).contains("Размер S", "пропал");
    }

    /**
     * When only some watched sizes change, the report carries only the changed ones — an unchanged
     * size stays out of the message.
     */
    @Test
    void reportsOnlyTheSizesThatActuallyChanged() {
        seed(Map.of("S", false, "M", true));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK), watch(1L, "M", AWAIT_RESTOCK)),
                Map.of("S", true, "M", true, "*", true), null);

        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(1);
        final var text = textOf(messages.getFirst());
        assertThat(text).contains("Размер S", "появился");
        assertThat(text).doesNotContain("Размер M");
    }

    /**
     * Two products changing across ticks: each is notified on its own transition, and changes that
     * land in the same tick (S sells out on A while L appears on B) fold into one report.
     */
    @Test
    void multiProductChangesAcrossTicksNotifyPerProductAndConsolidatePerTick() {
        final var keyB = "07654321";
        final var linkB = "https://www.zara.com/me/en/b-p07654321.html?v1=1";
        final var refB = new SubscriptionService.ProductRef(linkB, "Product B");

        when(subscriptionService.loadLastKnown()).thenReturn(Map.of(
                KEY, Map.of("S", false), keyB, Map.of("L", false)));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(Map.of());
        scheduler.seedLastKnown();

        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of(KEY, keyB)));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getProductRef(keyB)).thenReturn(refB);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(subscriptionService.getActiveWatches(keyB)).thenReturn(List.of(watch(1L, "L", AWAIT_RESTOCK)));

        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null))
                .thenReturn(new ProductSnapshot(Map.of("S", false, "*", false), null));
        when(pageService.checkProductSizesAvailability(linkB))
                .thenReturn(new ProductSnapshot(Map.of("L", false, "*", false), null))
                .thenReturn(new ProductSnapshot(Map.of("L", true, "*", true), null));

        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(2);
        assertThat(textOf(messages.get(0))).contains("Test product", "Размер S", "появился");
        assertThat(textOf(messages.get(0))).doesNotContain("Product B");
        assertThat(textOf(messages.get(1))).contains("Test product", "Размер S", "пропал");
        assertThat(textOf(messages.get(1))).contains("Product B", "Размер L", "появился");
    }

    /**
     * Two chats watching different sizes of the same product each get their own report — one scrape,
     * per-chat isolation.
     */
    @Test
    void severalChatsOnTheSameProductEachGetTheirOwnReport() {
        seed(Map.of("S", false, "M", false));
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK), watch(2L, "M", AWAIT_RESTOCK)),
                Map.of("S", true, "M", true, "*", true), null);

        scheduler.monitor();

        verify(pageService, times(1)).checkProductSizesAvailability(LINK);
        final var messages = sentMessages();
        assertThat(messages).hasSize(2);
        assertThat(messages).anySatisfy(m -> assertThat(textOf(m)).contains("Размер S", "появился"));
        assertThat(messages).anySatisfy(m -> assertThat(textOf(m)).contains("Размер M", "появился"));
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

    /**
     * Burst-confirm fetches the second confirming observation immediately (a re-scrape), so a real
     * change confirms and notifies within the same tick instead of waiting a whole period.
     */
    @Test
    void burstConfirmCommitsRealChangeInOneTick() {
        scheduler = burstScheduler(2, 3);
        seed(Map.of("S", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)));

        scheduler.monitor();

        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
    }

    /**
     * A blip that flips back within the burst window (obs0 in-stock, obs1 back to OOS) must not
     * notify — the fast confirmation still guards against a momentary flicker.
     */
    @Test
    void burstConfirmSuppressesBlipWithinWindow() {
        scheduler = burstScheduler(2, 3);
        seed(Map.of("S", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)));

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
    }

    /**
     * Burst-confirm exists only to speed up restock (OOS→in-stock) alerts; it must NOT accelerate a
     * sell-out. A watched in-stock size that reads OOS on both the tick scrape and the immediate
     * burst re-scrape is still a seconds-long blip (the two reads are ~3s apart, not an independent
     * cross-tick apart). Committing it — as burst once did — fired a false SIZE_SOLD_OUT, and the
     * next tick's revert fired the mirror SIZE_APPEARED: the notification spam. A sell-out must
     * instead wait for the cross-tick debounce, so burst does not even re-scrape and this tick stays
     * silent.
     */
    @Test
    void burstConfirmDoesNotAccelerateSellOut() {
        scheduler = burstScheduler(2, 3);
        seed(Map.of("S", true));
        stubProductSequence(List.of(watch(1L, "S", WATCH_IN_STOCK)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", false, "*", false)));

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(pageService, times(1)).checkProductSizesAvailability(LINK);
    }

    /**
     * When the confirming re-scrape fails (returns null), burst-confirm degrades gracefully to the
     * normal cross-tick debounce: silent on this tick, confirmed on the next — never worse than today.
     */
    @Test
    void burstConfirmFallsBackToCrossTickWhenRescrapeFails() {
        scheduler = burstScheduler(2, 3);
        seed(Map.of("S", false));
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null))
                .thenReturn(null)
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null));

        scheduler.monitor();
        verify(telegramBot, never()).execute(any(SendMessage.class));

        scheduler.monitor();
        assertThat(textOf(sentMessages().getFirst())).contains("Размер S", "появился");
    }

    /**
     * Burst-confirm only fires for a watched-size disagreement — a change to an unwatched size must
     * not trigger an extra re-scrape.
     */
    @Test
    void burstConfirmSkippedWhenOnlyUnwatchedSizesChange() {
        scheduler = burstScheduler(2, 3);
        seed(Map.of("S", true, "M", true));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "M", false, "*", true)));

        scheduler.monitor();

        verify(pageService, times(1)).checkProductSizesAvailability(LINK);
        verify(telegramBot, never()).execute(any(SendMessage.class));
    }

    /**
     * The per-tick burst budget caps how many products get the fast confirmation; the rest fall back
     * to the cross-tick debounce. With budget 1 and two products restocking, only the first notifies
     * this tick.
     */
    @Test
    void burstConfirmRespectsMaxPerTickBudget() {
        final var keyB = "07654321";
        final var linkB = "https://www.zara.com/me/en/b-p07654321.html?v1=1";
        final var refB = new SubscriptionService.ProductRef(linkB, "Product B");

        scheduler = burstScheduler(2, 1);

        when(subscriptionService.loadLastKnown()).thenReturn(Map.of(
                KEY, Map.of("S", false), keyB, Map.of("S", false)));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(Map.of());
        scheduler.seedLastKnown();

        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of(KEY, keyB)));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getProductRef(keyB)).thenReturn(refB);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(subscriptionService.getActiveWatches(keyB)).thenReturn(List.of(watch(2L, "S", AWAIT_RESTOCK)));
        when(pageService.checkProductSizesAvailability(LINK))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null));
        when(pageService.checkProductSizesAvailability(linkB))
                .thenReturn(new ProductSnapshot(Map.of("S", true, "*", true), null));

        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(1);
        assertThat(textOf(messages.getFirst())).contains("Test product", "Размер S", "появился");
    }

    @Test
    void burstConfirmIsOnByDefault() {
        assertThat(new ZaraProperties().getMonitor().isBurstConfirm()).isTrue();
    }

    @Test
    void doesNothingWithoutSubscriptions() {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of());

        scheduler.monitor();

        verifyNoInteractions(pageService, telegramBot);
    }

    /**
     * A single tick that runs longer than the slow-tick threshold (here forced by a scrape that
     * sleeps past it) alerts the admin — the signature of a synchronous Selenium fallback blocking
     * the single-threaded scheduler.
     */
    @Test
    void alertsAdminWhenATickIsSlow() {
        scheduler = watchdogScheduler(600_000, 50);
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(subscriptionService.getActiveWatches(KEY)).thenReturn(List.of(watch(1L, "S", AWAIT_RESTOCK)));
        when(pageService.checkProductSizesAvailability(LINK)).thenAnswer(invocation -> {
            Thread.sleep(120);
            return new ProductSnapshot(Map.of("S", false, "*", false), null);
        });

        scheduler.monitor();

        verify(adminNotifier).alert(eq("slow-tick"), any());
    }

    @Test
    void doesNotAlertSlowTickForAFastTick() {
        scheduler = watchdogScheduler(600_000, 50);
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", false, "*", false), null);

        scheduler.monitor();

        verify(adminNotifier, never()).alert(eq("slow-tick"), any());
    }

    /**
     * When ticks resume after a gap longer than the stall threshold (the host was suspended / the
     * container paused), the resuming tick reports the outage window to the admin. Simulated with a
     * tiny threshold and a real pause between two ticks; the first tick must never trip it.
     */
    @Test
    void alertsAdminWhenMonitoringResumesAfterAGap() throws InterruptedException {
        scheduler = watchdogScheduler(1, 600_000);
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", false, "*", false), null);

        scheduler.monitor();
        verify(adminNotifier, never()).alert(eq("monitoring-gap"), any());

        Thread.sleep(15);
        scheduler.monitor();

        verify(adminNotifier).alert(eq("monitoring-gap"), any());
    }

    @Test
    void doesNotAlertGapOnNormalCadence() {
        scheduler = watchdogScheduler(600_000, 600_000);
        stubProduct(List.of(watch(1L, "S", AWAIT_RESTOCK)), Map.of("S", false, "*", false), null);

        scheduler.monitor();
        scheduler.monitor();

        verify(adminNotifier, never()).alert(eq("monitoring-gap"), any());
    }

    /**
     * Anti-flap must stay opt-in: the quarantine can mute a genuine rapid restock, so — like
     * {@code confirmRestockViaSelenium} — it is off unless deliberately enabled. Guards the default.
     */
    @Test
    void antiFlapIsOffByDefault() {
        assertThat(new ZaraProperties().getMonitor().getAntiFlapCooldownTicks()).isZero();
    }

    /**
     * The core spam fix. A genuinely-OOS product whose API flickers in-stock for a few seconds gets
     * one false appeared/sold-out pair (unavoidable — the first flicker looks like a real restock),
     * then the flap is detected (the sold-out reverses the appeared within the cooldown window) and
     * the size is quarantined: every later in-stock flicker is barred from burst-confirm and, even if
     * it slips through the cross-tick debounce, its restock is reverted and never notified. So the
     * user gets exactly two messages, not the endless SOLD_OUT↔APPEARED oscillation from the log.
     * <p>
     * Sequence (burst re-scrapes consume a snapshot too): t1 in-stock ×2 → burst APPEARED; t2/t3
     * out-of-stock → cross-tick SOLD_OUT + flap detected; t4/t5 the flicker returns in-stock but the
     * size is quarantined, so nothing more fires.
     */
    @Test
    void flapQuarantineSilencesOscillationAfterTheFirstPair() {
        scheduler = antiFlapScheduler(2, 3, 5);
        seed(Map.of("S", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)));

        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();

        final var messages = sentMessages();
        assertThat(messages).hasSize(2);
        assertThat(textOf(messages.get(0))).contains("Размер S", "появился");
        assertThat(textOf(messages.get(1))).contains("Размер S", "пропал");
    }

    /**
     * When a flap is detected and the size quarantined, the admin chat is pinged (throttled per
     * product+size) so the operator knows which product is flickering — not just silence.
     */
    @Test
    void alertsAdminWhenASizeIsQuarantinedAsAFlapper() {
        scheduler = antiFlapScheduler(2, 3, 5);
        seed(Map.of("S", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", false, "*", false)));

        scheduler.monitor();
        scheduler.monitor();
        scheduler.monitor();

        verify(adminNotifier).alert(eq("flap:" + KEY + ":S"), any());
    }

    /**
     * Quarantine is a timed mute, not a permanent one: once it elapses, a genuine restock alerts
     * again. With cooldown/quarantine of 2 ticks, the flap at t1–t3 quarantines S until t5; a stable
     * restock from t6 onward must produce a fresh "appeared".
     */
    @Test
    void sizeRecoversFromQuarantineAndAlertsAGenuineRestock() {
        scheduler = antiFlapScheduler(2, 2, 2);
        seed(Map.of("S", false));
        stubProductSequence(List.of(watch(1L, "S", AWAIT_RESTOCK)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", false, "*", false)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)),
                snap(Map.of("S", true, "*", true)));

        for (int i = 0; i < 8; i++) {
            scheduler.monitor();
        }

        final var messages = sentMessages();
        assertThat(messages).hasSize(3);
        assertThat(textOf(messages.get(0))).contains("Размер S", "появился");
        assertThat(textOf(messages.get(1))).contains("Размер S", "пропал");
        assertThat(textOf(messages.get(2))).contains("Размер S", "появился");
    }
}
