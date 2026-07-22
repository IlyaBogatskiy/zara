package com.ibdev.bot.zara.telegram;

import com.google.gson.Gson;
import com.ibdev.bot.zara.cache.ProductCardCache;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.SizeInfo;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.BaseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.USER_ACTION;
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
    private final ChatDirectory chatDirectory = new ChatDirectory();
    private final Gson gson = new Gson();

    @Mock
    private AdminStatusReporter adminStatusReporter;

    private UpdatesListener listener;

    @BeforeEach
    void captureListener() {
        final var bot = new ZaraTelegramListener(
                sessionCache, telegramBot, productCardCache, subscriptionService, scrapingExecutor,
                new AdminCommandHandler(subscriptionService, new ZaraProperties(), chatDirectory), chatDirectory,
                adminStatusReporter
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
        return new ProductCard(KEY, "Test product", LINK, List.of(sizes), null);
    }

    private List<BaseRequest> requests(final int atLeast) {
        final var captor = ArgumentCaptor.forClass(BaseRequest.class);
        verify(telegramBot, timeout(3000).atLeast(atLeast)).execute(captor.capture());
        return captor.getAllValues();
    }

    private InlineKeyboardMarkup markupOf(final BaseRequest<?, ?> request) {
        return (InlineKeyboardMarkup) request.getParameters().get("reply_markup");
    }

    private List<String> callbackDatas(final InlineKeyboardMarkup markup) {
        return Arrays.stream(markup.inlineKeyboard())
                .flatMap(Arrays::stream)
                .map(InlineKeyboardButton::callbackData)
                .filter(Objects::nonNull)
                .toList();
    }

    private String textOf(final BaseRequest<?, ?> request) {
        return (String) request.getParameters().get("text");
    }

    @Test
    void startCommandSendsGreeting() {
        sendText("/start");

        final var sent = requests(1);
        assertThat(textOf(sent.getFirst())).contains("Привет");
    }

    @Test
    void nonLinkTextAsksForZaraLink() {
        sendText("просто текст");

        assertThat(textOf(requests(1).getFirst())).contains("Пришли ссылку");
    }

    @Test
    void zaraLinkLoadsCardOffThreadAndOffersTracking() {
        when(productCardCache.getOrLoad(KEY, LINK))
                .thenReturn(card(new SizeInfo("S", false), new SizeInfo("M", false)));

        sendText(LINK);

        final var sent = requests(2);
        assertThat(textOf(sent.getFirst())).contains("⏳");
        assertThat(textOf(sent.get(1))).contains("Test product", "отслеживать");
        assertThat(sent.get(1).getParameters().get("reply_markup")).isNotNull();
    }

    @Test
    void cardWithAllSizesInStockStillOffersTracking() {
        when(productCardCache.getOrLoad(KEY, LINK))
                .thenReturn(card(new SizeInfo("S", true), new SizeInfo("M", true)));

        sendText(LINK);

        final var sent = requests(2);
        assertThat(textOf(sent.get(1))).contains("отслеживать");
        assertThat(sent.get(1).getParameters().get("reply_markup")).isNotNull();
    }

    @Test
    void picksInStockSizeAndConfirmationShowsPriceWatchPlan() {
        final var loaded = card(new SizeInfo("S", false), new SizeInfo("M", true));
        when(productCardCache.getOrLoad(KEY, LINK)).thenReturn(loaded);
        when(subscriptionService.getSubscribedSizes(CHAT, KEY)).thenReturn(Set.of());

        sendText(LINK);
        requests(2);

        sendCallback("TRACK");
        sendCallback("TOGGLE:M");
        sendCallback("CONFIRM");

        verify(subscriptionService, timeout(3000)).subscribe(eq(CHAT), eq(loaded), eq(Set.of("M")));

        final var planShown = requests(1).stream()
                .map(this::textOf)
                .filter(Objects::nonNull)
                .anyMatch(t -> t.contains("Слежу за ценой") && t.contains("M"));
        assertThat(planShown).as("подтверждение показывает план 'слежу за ценой'").isTrue();
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
    void wholeUnavailableStopButtonUnsubscribesWholeProduct() {
        sendCallback("WHOLE_STOP:" + KEY);

        verify(subscriptionService).unsubscribe(CHAT, KEY, Set.of("*"), USER_ACTION);
        assertThat(textOf(requests(1).getFirst())).contains("больше не слежу");
    }

    @Test
    void wholeUnavailableContinueButtonKeepsWatchingWithoutChange() {
        sendCallback("WHOLE_CONTINUE:" + KEY);

        verify(subscriptionService, never()).unsubscribe(anyLong(), any(), any(), any());
        assertThat(textOf(requests(1).getFirst())).contains("Продолжаю следить");
    }

    @Test
    void staleCallbackWithoutSessionCardAsksToReopen() {
        sendCallback("CONFIRM");

        assertThat(textOf(requests(1).getFirst())).contains("Контекст устарел");
        verify(subscriptionService, never()).subscribe(anyLong(), any(), any());
    }

    @Test
    void sizeAppearedKeepButtonSwitchesToWatchInStock() {
        when(subscriptionService.watchInStock(CHAT, KEY, "S")).thenReturn(true);

        sendCallback("SIZE_WATCH:" + KEY + ":S");

        verify(subscriptionService).watchInStock(CHAT, KEY, "S");
        assertThat(textOf(requests(1).getFirst())).contains("Слежу за размером S");
    }

    @Test
    void sizeAppearedStopButtonUnsubscribes() {
        sendCallback("SIZE_STOP:" + KEY + ":S");

        verify(subscriptionService).unsubscribe(CHAT, KEY, Set.of("S"), USER_ACTION);
        assertThat(textOf(requests(1).getFirst())).contains("больше не слежу за размером S");
    }

    @Test
    void sizeSoldOutKeepButtonSwitchesBackToAwaitRestock() {
        when(subscriptionService.awaitRestock(CHAT, KEY, "S")).thenReturn(true);

        sendCallback("SIZE_AWAIT:" + KEY + ":S");

        verify(subscriptionService).awaitRestock(CHAT, KEY, "S");
        assertThat(textOf(requests(1).getFirst())).contains("ждать появления размера S");
    }

    @Test
    void sizeDecisionForUnknownSubscriptionAsksToResend() {
        when(subscriptionService.watchInStock(CHAT, KEY, "S")).thenReturn(false);

        sendCallback("SIZE_WATCH:" + KEY + ":S");

        assertThat(textOf(requests(1).getFirst())).contains("Не нашёл эту подписку");
    }

    @Test
    void filterOutButtonsDropsOnlyMatchingItemAndRemovesEmptyRows() {
        final var markup = new InlineKeyboardMarkup()
                .addRow(
                        new InlineKeyboardButton("watch S").callbackData("SIZE_WATCH:" + KEY + ":S"),
                        new InlineKeyboardButton("stop S").callbackData("SIZE_STOP:" + KEY + ":S")
                )
                .addRow(
                        new InlineKeyboardButton("watch M").callbackData("SIZE_WATCH:" + KEY + ":M"),
                        new InlineKeyboardButton("stop M").callbackData("SIZE_STOP:" + KEY + ":M")
                );

        final var filtered = ZaraTelegramListener.filterOutButtons(markup, Set.of(
                "SIZE_WATCH:" + KEY + ":S",
                "SIZE_AWAIT:" + KEY + ":S",
                "SIZE_STOP:" + KEY + ":S"
        ));

        final var datas = Arrays.stream(filtered.inlineKeyboard())
                .flatMap(Arrays::stream)
                .map(InlineKeyboardButton::callbackData)
                .toList();
        assertThat(filtered.inlineKeyboard().length).isEqualTo(1);
        assertThat(datas).containsExactly("SIZE_WATCH:" + KEY + ":M", "SIZE_STOP:" + KEY + ":M");
    }

    @Test
    void tappingSubscriptionResolvesWithoutPriorSessionState() {
        when(subscriptionService.getSubscribedSizes(CHAT, KEY)).thenReturn(Set.of("S", "M"));
        when(subscriptionService.getSubscribedSizeModes(CHAT, KEY)).thenReturn(Map.of("S", AWAIT_RESTOCK, "M", AWAIT_RESTOCK));
        when(subscriptionService.getProductRef(KEY))
                .thenReturn(new SubscriptionService.ProductRef(LINK, "Test product"));

        sendCallback("SUB_OPEN:" + KEY);

        final var last = requests(1).getLast();
        assertThat(textOf(last)).contains("Отслеживаемые размеры");
        assertThat(textOf(last)).doesNotContain("устарел");
    }

    @Test
    void subscriptionsMenuOpensDetailsThenReturnsToList() {
        when(subscriptionService.getAllSubscribedSizes(CHAT)).thenReturn(Map.of(KEY, Set.of("S", "M")));
        when(subscriptionService.getSubscribedSizeModes(CHAT, KEY))
                .thenReturn(Map.of("S", WATCH_IN_STOCK, "M", AWAIT_RESTOCK));
        when(subscriptionService.getSubscribedSizes(CHAT, KEY)).thenReturn(Set.of("S", "M"));
        when(subscriptionService.getProductRef(KEY))
                .thenReturn(new SubscriptionService.ProductRef(LINK, "Test product"));

        sendText("📌 Подписки");
        final var listData = callbackDatas(markupOf(requests(1).getLast()));
        final var openData = listData.stream().filter(d -> d.startsWith("SUB_OPEN:")).findFirst().orElseThrow();

        sendCallback(openData);
        final var details = requests(2).getLast();
        assertThat(textOf(details)).contains("Отслеживаемые размеры");
        final var detailsData = callbackDatas(markupOf(details));
        assertThat(detailsData).anyMatch(d -> d.startsWith("SUB_TOGGLE:"));
        assertThat(detailsData).contains("SUBS_MENU");

        sendCallback("SUBS_MENU");
        assertThat(textOf(requests(3).getLast())).contains("Ваши подписки");
    }

    @Test
    void subscriptionsMenuTogglingASizeUnsubscribesAndRefreshesDetails() {
        when(subscriptionService.getAllSubscribedSizes(CHAT)).thenReturn(Map.of(KEY, Set.of("S", "M")));
        when(subscriptionService.getSubscribedSizeModes(CHAT, KEY)).thenReturn(Map.of("S", AWAIT_RESTOCK, "M", AWAIT_RESTOCK));
        when(subscriptionService.getSubscribedSizes(CHAT, KEY)).thenReturn(Set.of("S", "M"));
        when(subscriptionService.getProductRef(KEY))
                .thenReturn(new SubscriptionService.ProductRef(LINK, "Test product"));

        sendText("📌 Подписки");
        final var openData = callbackDatas(markupOf(requests(1).getLast())).stream()
                .filter(d -> d.startsWith("SUB_OPEN:")).findFirst().orElseThrow();
        sendCallback(openData);

        final var toggleData = callbackDatas(markupOf(requests(2).getLast())).stream()
                .filter(d -> d.startsWith("SUB_TOGGLE:") && d.endsWith(":S")).findFirst().orElseThrow();
        sendCallback(toggleData);

        verify(subscriptionService).unsubscribe(CHAT, KEY, Set.of("S"), USER_ACTION);
    }

    @Test
    void subscriptionsMenuMarksInStockPriceWatchesWithCoin() {
        when(subscriptionService.getAllSubscribedSizes(CHAT)).thenReturn(Map.of(KEY, Set.of("S", "M")));
        when(subscriptionService.getSubscribedSizeModes(CHAT, KEY))
                .thenReturn(Map.of("S", WATCH_IN_STOCK, "M", AWAIT_RESTOCK));

        sendText("📌 Подписки");

        final var text = textOf(requests(1).getFirst());
        assertThat(text).contains("S 💰");
        assertThat(text).doesNotContain("M 💰");
        assertThat(text).contains("💰 — слежу за ценой");
    }

    // --- admin menu (separate from the user menu) ---

    private UpdatesListener adminListener() {
        final var props = new ZaraProperties();
        props.setAdminChatId(CHAT);
        new ZaraTelegramListener(sessionCache, telegramBot, productCardCache, subscriptionService, scrapingExecutor,
                new AdminCommandHandler(subscriptionService, props, chatDirectory), chatDirectory,
                adminStatusReporter).init();
        final var captor = ArgumentCaptor.forClass(UpdatesListener.class);
        verify(telegramBot, atLeastOnce()).setUpdatesListener(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private void processText(final UpdatesListener l, final String text) {
        l.process(List.of(gson.fromJson(
                "{\"message\":{\"message_id\":10,\"chat\":{\"id\":" + CHAT + "},\"text\":\"" + text + "\"}}",
                Update.class)));
    }

    private void processCallback(final UpdatesListener l, final String data) {
        l.process(List.of(gson.fromJson(
                "{\"callback_query\":{\"id\":\"cb\",\"data\":\"" + data + "\"," +
                        "\"message\":{\"message_id\":10,\"chat\":{\"id\":" + CHAT + "}}}}",
                Update.class)));
    }

    private void stubAdminData() {
        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of("K1", "K2")));
        when(subscriptionService.getActiveWatches("K1"))
                .thenReturn(List.of(new SubscriptionService.Watch(1L, "S", AWAIT_RESTOCK),
                        new SubscriptionService.Watch(2L, "M", WATCH_IN_STOCK)));
        when(subscriptionService.getActiveWatches("K2"))
                .thenReturn(List.of(new SubscriptionService.Watch(1L, "L", AWAIT_RESTOCK)));
        when(subscriptionService.getSubscribersByProduct("K1")).thenReturn(Map.of(1L, Set.of("S"), 2L, Set.of("M")));
        when(subscriptionService.getSubscribersByProduct("K2")).thenReturn(Map.of(1L, Set.of("L")));
        when(subscriptionService.findProductRef("K1"))
                .thenReturn(new SubscriptionService.ProductRef("https://z/k1", "Куртка"));
        when(subscriptionService.findProductRef("K2"))
                .thenReturn(new SubscriptionService.ProductRef("https://z/k2", "Джинсы"));
        when(subscriptionService.loadLastKnownPrices()).thenReturn(Map.of());
    }

    @Test
    void adminProductsButtonShowsPerProductButtons() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "📦 Товары");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_PRODUCT:K1", "ADM_PRODUCT:K2");
    }

    @Test
    void adminProductCallbackShowsDetailsWithBackButton() {
        stubAdminData();
        final var admin = adminListener();

        processCallback(admin, "ADM_PRODUCT:K1");

        final var msg = requests(1).getLast();
        assertThat(textOf(msg)).contains("Куртка", "чат 1");
        assertThat(callbackDatas(markupOf(msg))).contains("ADM_PRODUCTS");
    }

    @Test
    void adminChatsButtonShowsPerChatButtons() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "👤 Чаты");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_CHAT:1", "ADM_CHAT:2");
    }

    @Test
    void adminStatsButtonShowsOverview() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "📊 Статистика");

        assertThat(textOf(requests(1).getLast())).contains("Товаров в мониторинге", "Уникальных чатов");
    }

    @Test
    void adminProductLinkDoesNotEnterUserFlow() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "https://www.zara.com/x-p12345.html");

        verify(productCardCache, never()).getOrLoad(any(), any());
    }

    @Test
    void nonAdminCannotUseAdminCallback() {
        sendCallback("ADM_PRODUCTS");

        verify(subscriptionService, never()).getSubscribersByProduct(any());
    }

    @Test
    void adminProductsFirstPageShowsEightItemsAndNextNav() {
        final var keys = new LinkedHashSet<String>();
        for (int i = 0; i < 20; i++) {
            keys.add(String.format("K%02d", i));
        }
        when(subscriptionService.activeProductKeys()).thenReturn(keys);
        final var admin = adminListener();

        processText(admin, "📦 Товары");

        final var data = callbackDatas(markupOf(requests(1).getLast()));
        assertThat(data).contains("ADM_PRODUCT:K00", "ADM_PRODUCT:K07", "ADM_PROD_PAGE:1");
        assertThat(data).doesNotContain("ADM_PRODUCT:K08");
    }

    @Test
    void adminProductsSecondPageShowsPrevAndNextNav() {
        final var keys = new LinkedHashSet<String>();
        for (int i = 0; i < 20; i++) {
            keys.add(String.format("K%02d", i));
        }
        when(subscriptionService.activeProductKeys()).thenReturn(keys);
        final var admin = adminListener();

        processCallback(admin, "ADM_PROD_PAGE:1");

        final var data = callbackDatas(markupOf(requests(1).getLast()));
        assertThat(data).contains("ADM_PRODUCT:K08", "ADM_PRODUCT:K15", "ADM_PROD_PAGE:0", "ADM_PROD_PAGE:2");
        assertThat(data).doesNotContain("ADM_PRODUCT:K07", "ADM_PRODUCT:K16");
    }

    @Test
    void adminProductsSinglePageHasNoNav() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "📦 Товары");

        final var data = callbackDatas(markupOf(requests(1).getLast()));
        assertThat(data).contains("ADM_PRODUCT:K1", "ADM_PRODUCT:K2");
        assertThat(data).doesNotContain("ADM_PROD_PAGE:1", "ADM_NOOP");
    }

    @Test
    void adminChatsPaginate() {
        final var subs = new java.util.HashMap<Long, Set<String>>();
        for (long i = 1; i <= 20; i++) {
            subs.put(i, Set.of("S"));
        }
        when(subscriptionService.activeProductKeys()).thenReturn(new LinkedHashSet<>(List.of("K1")));
        when(subscriptionService.getSubscribersByProduct("K1")).thenReturn(subs);
        final var admin = adminListener();

        processText(admin, "👤 Чаты");

        final var data = callbackDatas(markupOf(requests(1).getLast()));
        assertThat(data).contains("ADM_CHAT:1", "ADM_CHAT:8", "ADM_CHAT_PAGE:1");
        assertThat(data).doesNotContain("ADM_CHAT:9");
    }

    @Test
    void adminProductDetailsHasRefreshAndBack() {
        stubAdminData();
        final var admin = adminListener();

        processCallback(admin, "ADM_PRODUCT:K1");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_PRODUCTS", "ADM_PRODUCT:K1");
    }

    @Test
    void adminChatDetailsHasRefreshAndBack() {
        stubAdminData();
        final var admin = adminListener();

        processCallback(admin, "ADM_CHAT:1");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_CHATS", "ADM_CHAT:1");
    }

    @Test
    void adminProductsListHasRefresh() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "📦 Товары");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_PROD_PAGE:0");
    }

    @Test
    void adminStatsShowsRefreshButton() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "📊 Статистика");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_STATS");
    }

    @Test
    void adminStatsRefreshReRendersOverview() {
        stubAdminData();
        final var admin = adminListener();

        processCallback(admin, "ADM_STATS");

        assertThat(textOf(requests(1).getLast())).contains("Товаров в мониторинге");
    }

    @Test
    void adminFindUnifiedReturnsProductsAndChats() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "/find 1");

        assertThat(callbackDatas(markupOf(requests(1).getLast()))).contains("ADM_PRODUCT:K1", "ADM_CHAT:1");
    }

    @Test
    void adminFindProductScopeReturnsOnlyProducts() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "/fp 1");

        final var data = callbackDatas(markupOf(requests(1).getLast()));
        assertThat(data).contains("ADM_PRODUCT:K1");
        assertThat(data).noneMatch(d -> d.startsWith("ADM_CHAT:"));
    }

    @Test
    void adminFindChatScopeReturnsOnlyChats() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "/fc 1");

        final var data = callbackDatas(markupOf(requests(1).getLast()));
        assertThat(data).contains("ADM_CHAT:1");
        assertThat(data).noneMatch(d -> d.startsWith("ADM_PRODUCT:"));
    }

    @Test
    void adminSearchButtonPromptsThenNextMessageSearches() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "🔎 Поиск");
        processText(admin, "курт");

        assertThat(callbackDatas(markupOf(requests(2).getLast()))).contains("ADM_PRODUCT:K1");
    }

    @Test
    void adminFindNoMatchReports() {
        stubAdminData();
        final var admin = adminListener();

        processText(admin, "/find zzz");

        assertThat(textOf(requests(1).getLast())).contains("не найдено");
    }

    @Test
    void adminStatusButtonShowsStatusWithRefresh() {
        when(adminStatusReporter.render()).thenReturn("🩺 Статус мониторинга\nТик #1");
        final var admin = adminListener();

        processText(admin, "🩺 Статус");

        final var msg = requests(1).getLast();
        assertThat(textOf(msg)).contains("Статус мониторинга");
        assertThat(callbackDatas(markupOf(msg))).contains("ADM_STATUS");
    }

    @Test
    void adminStatusRefreshCallbackReRenders() {
        when(adminStatusReporter.render()).thenReturn("🩺 Статус мониторинга\nТик #2");
        final var admin = adminListener();

        processCallback(admin, "ADM_STATUS");

        assertThat(textOf(requests(1).getLast())).contains("Статус мониторинга");
    }

    @Test
    void adminEventsButtonShowsRecentWithRefresh() {
        when(adminStatusReporter.renderRecent()).thenReturn("🕓 Последние события:\n2м назад · ✅ Куртка");
        final var admin = adminListener();

        processText(admin, "🕓 События");

        final var msg = requests(1).getLast();
        assertThat(textOf(msg)).contains("Последние события");
        assertThat(callbackDatas(markupOf(msg))).contains("ADM_EVENTS");
    }

    @Test
    void adminEventsRefreshCallbackReRenders() {
        when(adminStatusReporter.renderRecent()).thenReturn("🕓 Последние события:\n…");
        final var admin = adminListener();

        processCallback(admin, "ADM_EVENTS");

        assertThat(textOf(requests(1).getLast())).contains("Последние события");
    }

    @Test
    void capturesUsernameFromIncomingMessage() {
        listener.process(List.of(gson.fromJson(
                "{\"message\":{\"message_id\":10,\"chat\":{\"id\":" + CHAT
                        + ",\"username\":\"bob\",\"first_name\":\"Bob\"},\"text\":\"/start\"}}",
                Update.class)));

        assertThat(chatDirectory.label(CHAT)).isEqualTo("чат " + CHAT + " · @bob");
    }
}
