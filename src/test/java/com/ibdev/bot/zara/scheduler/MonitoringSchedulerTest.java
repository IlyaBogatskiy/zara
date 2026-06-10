package com.ibdev.bot.zara.scheduler;

import com.ibdev.bot.zara.notify.UserNotifier;
import com.ibdev.bot.zara.service.page.PageService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the monitoring transition logic: "was out -> now in" = notification
 * + auto-unsubscribe, everything else stays silent. No Selenium, network or DB.
 *
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

    private void stubProduct(final Map<Long, Set<String>> subscribers, final Map<String, Boolean> current) {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of(KEY));
        when(subscriptionService.getProductRef(KEY)).thenReturn(REF);
        when(pageService.checkProductSizesAvailability(LINK)).thenReturn(current);
        when(subscriptionService.getSubscribersByProduct(KEY)).thenReturn(subscribers);
    }

    private void seed(final Map<String, Boolean> persisted) {
        when(subscriptionService.loadLastKnown()).thenReturn(Map.of(KEY, persisted));
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
    void notifiesAndUnsubscribesWhenSizeAppears() {
        seed(Map.of("S", false));
        stubProduct(Map.of(1L, Set.of("S")), Map.of("S", true, "*", true));

        scheduler.monitor();

        assertThat(textOf(sentMessages().get(0))).contains("Размер S", "Test product");
        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("S"), AUTO_AVAILABLE);
        verify(subscriptionService).recordCheck(KEY, Map.of("S", true, "*", true));
    }

    @Test
    void staysSilentWhileSizeIsStillOutOfStock() {
        seed(Map.of("S", false));
        stubProduct(Map.of(1L, Set.of("S")), Map.of("S", false, "*", false));

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
        verify(subscriptionService).recordCheck(KEY, Map.of("S", false, "*", false));
    }

    @Test
    void noDuplicateNotificationAfterRestartWhenAlreadyInStock() {
        seed(Map.of("S", true));
        stubProduct(Map.of(1L, Set.of("S")), Map.of("S", true, "*", true));

        scheduler.monitor();

        verify(telegramBot, never()).execute(any(SendMessage.class));
        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
    }

    @Test
    void absentHistoryIsTreatedAsOutOfStock() {
        stubProduct(Map.of(1L, Set.of("S")), Map.of("S", true, "*", true));

        scheduler.monitor();

        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("S"), AUTO_AVAILABLE);
    }

    @Test
    void matchesSizesFuzzily() {
        seed(Map.of("40", false));
        stubProduct(Map.of(1L, Set.of("40")), Map.of("EU40", true, "*", true));

        scheduler.monitor();

        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("40"), AUTO_AVAILABLE);
    }

    @Test
    void notifiesEverySubscribedChatWithOneScrape() {
        seed(Map.of("S", false, "M", false));
        stubProduct(
                Map.of(1L, Set.of("S"), 2L, Set.of("S", "M")),
                Map.of("S", true, "M", false, "*", true)
        );

        scheduler.monitor();

        verify(pageService, times(1)).checkProductSizesAvailability(LINK);
        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("S"), AUTO_AVAILABLE);
        verify(subscriptionService).unsubscribe(2L, KEY, Set.of("S"), AUTO_AVAILABLE);
        verify(subscriptionService, never()).unsubscribe(eq(2L), eq(KEY), eq(Set.of("M")), any());
    }

    @Test
    void wholeProductWithMissingSizesOffersKeepMonitoringButton() {
        stubProduct(Map.of(1L, Set.of("*")), Map.of("S", true, "M", false, "*", true));

        scheduler.monitor();

        final var message = sentMessages().get(0);
        assertThat(textOf(message)).contains("Товар появился", "[M]", "отсутствующими размерами");
        assertThat(message.getParameters().get("reply_markup")).isNotNull();
        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("*"), AUTO_AVAILABLE);
    }

    @Test
    void wholeProductFullyAvailableJustStopsMonitoring() {
        stubProduct(Map.of(1L, Set.of("*")), Map.of("S", true, "M", true, "*", true));

        scheduler.monitor();

        final var message = sentMessages().get(0);
        assertThat(textOf(message)).contains("остановил мониторинг");
        assertThat(message.getParameters().get("reply_markup")).isNull();
        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("*"), AUTO_AVAILABLE);
    }

    @Test
    void wholeProductStillUnavailableStaysSilent() {
        stubProduct(Map.of(1L, Set.of("*")), Map.of("S", false, "*", false));

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
        when(pageService.checkProductSizesAvailability(LINK)).thenReturn(Map.of("S", true, "*", true));
        when(subscriptionService.getSubscribersByProduct(KEY)).thenReturn(Map.of(1L, Set.of("S")));

        scheduler.monitor();

        verify(subscriptionService).unsubscribe(1L, KEY, Set.of("S"), AUTO_AVAILABLE);
    }

    @Test
    void doesNothingWithoutSubscriptions() {
        when(subscriptionService.activeProductKeys()).thenReturn(Set.of());

        scheduler.monitor();

        verifyNoInteractions(pageService, telegramBot);
    }
}
