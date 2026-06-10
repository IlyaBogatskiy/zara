package com.ibdev.bot.zara.telegram;

import com.google.gson.Gson;
import com.ibdev.bot.zara.cache.ProductCardCache;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.SizeInfo;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.SendMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Telegram flow tests: updates are fed into the captured UpdatesListener,
 * pengrad models are built from JSON (their fields are snake_case for Gson).
 * Scraping is mocked; the ScrapingExecutor is real, hence timeout-based verifications.
 *
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZaraTelegramListenerTest {

    private static final long CHAT = 555L;
    private static final String LINK = "https://www.zara.com/me/en/test-p03992419.html?v1=1";
    private static final String KEY = "03992419";

    @Mock
    private TelegramBot telegramBot;

    @Mock
    private ProductCardCache productCardCache;

    @Mock
    private SubscriptionService subscriptionService;

    private final SessionCache sessionCache = new SessionCache();
    private final ScrapingExecutor scrapingExecutor = new ScrapingExecutor();
    private final Gson gson = new Gson();

    private UpdatesListener listener;

    @BeforeEach
    void captureListener() {
        final var bot = new ZaraTelegramListener(
                sessionCache, telegramBot, productCardCache, subscriptionService, scrapingExecutor
        );
        bot.init();

        final var captor = ArgumentCaptor.forClass(UpdatesListener.class);
        verify(telegramBot).setUpdatesListener(captor.capture());
        listener = captor.getValue();
    }

    private void sendText(final String text) {
        final var update = gson.fromJson(
                "{\"message\":{\"message_id\":10,\"chat\":{\"id\":" + CHAT + "},\"text\":\"" + text + "\"}}",
                Update.class
        );
        listener.process(List.of(update));
    }

    private void sendCallback(final String data) {
        final var update = gson.fromJson(
                "{\"callback_query\":{\"id\":\"cb\",\"data\":\"" + data + "\"," +
                        "\"message\":{\"message_id\":10,\"chat\":{\"id\":" + CHAT + "}}}}",
                Update.class
        );
        listener.process(List.of(update));
    }

    private ProductCard card(final SizeInfo... sizes) {
        return new ProductCard(KEY, "Test product", LINK, List.of(sizes));
    }

    private List<BaseRequest> requests(final int atLeast) {
        final var captor = ArgumentCaptor.forClass(BaseRequest.class);
        verify(telegramBot, timeout(3000).atLeast(atLeast)).execute(captor.capture());
        return captor.getAllValues();
    }

    private String textOf(final BaseRequest<?, ?> request) {
        return (String) request.getParameters().get("text");
    }

    @Test
    void startCommandSendsGreeting() {
        sendText("/start");

        final var sent = requests(1);
        assertThat(textOf(sent.get(0))).contains("Привет");
    }

    @Test
    void nonLinkTextAsksForZaraLink() {
        sendText("просто текст");

        assertThat(textOf(requests(1).get(0))).contains("Пришли ссылку");
    }

    @Test
    void zaraLinkLoadsCardOffThreadAndOffersTracking() {
        when(productCardCache.getOrLoad(KEY, LINK))
                .thenReturn(card(new SizeInfo("S", false), new SizeInfo("M", false)));

        sendText(LINK);

        final var sent = requests(2);
        assertThat(textOf(sent.get(0))).contains("⏳");
        assertThat(textOf(sent.get(1))).contains("Test product", "отслеживать");
        assertThat(sent.get(1).getParameters().get("reply_markup")).isNotNull();
    }

    @Test
    void cardWithAllSizesInStockNeedsNoTracking() {
        when(productCardCache.getOrLoad(KEY, LINK)).thenReturn(card());

        sendText(LINK);

        final var sent = requests(2);
        assertThat(textOf(sent.get(1))).contains("все размеры в наличии");
    }

    @Test
    void fullSubscribeFlowTrackToggleConfirm() {
        final var loaded = card(new SizeInfo("S", false), new SizeInfo("M", false));
        when(productCardCache.getOrLoad(KEY, LINK)).thenReturn(loaded);
        when(subscriptionService.getSubscribedSizes(CHAT, KEY)).thenReturn(Set.of());

        sendText(LINK);
        requests(2);

        sendCallback("TRACK");
        sendCallback("TOGGLE:S");
        sendCallback("CONFIRM");

        verify(subscriptionService, timeout(3000)).subscribe(eq(CHAT), eq(loaded), eq(Set.of("S")));
    }

    @Test
    void wholeKeepButtonResubscribesToMissingSizes() {
        when(subscriptionService.findProductRef(KEY))
                .thenReturn(new SubscriptionService.ProductRef(LINK, "Test product"));
        when(productCardCache.getOrLoad(KEY, LINK))
                .thenReturn(card(new SizeInfo("S", false), new SizeInfo("M", false)));

        sendCallback("WHOLE_KEEP:" + KEY);

        verify(subscriptionService, timeout(3000)).subscribe(eq(CHAT), any(ProductCard.class), eq(Set.of("S", "M")));
        verify(productCardCache, timeout(3000)).invalidate(KEY);
    }

    @Test
    void staleCallbackWithoutSessionCardAsksToReopen() {
        sendCallback("CONFIRM");

        assertThat(textOf(requests(1).get(0))).contains("Контекст устарел");
        verify(subscriptionService, never()).subscribe(anyLong(), any(), any());
    }
}
