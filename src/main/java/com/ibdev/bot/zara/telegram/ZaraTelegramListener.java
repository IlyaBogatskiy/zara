package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.cache.ProductCardCache;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.SizeInfo;
import com.ibdev.bot.zara.notify.UserNotifier;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.storage.model.SubscriptionMode;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.KeyboardButton;
import com.pengrad.telegrambot.model.request.ReplyKeyboardMarkup;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.EditMessageReplyMarkup;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.USER_ACTION;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.WATCH_IN_STOCK;
import static com.ibdev.bot.zara.util.ProductLinks.extractProductId;

/**
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ZaraTelegramListener {

    private static final String CB_TRACK = "TRACK";
    private static final String CB_CANCEL = "CANCEL";
    private static final String CB_BACK = "BACK";
    private static final String CB_CONFIRM = "CONFIRM";
    private static final String CB_TOGGLE_PREFIX = "TOGGLE:";
    private static final String CB_TRACK_WHOLE = "TRACK_WHOLE";
    private static final String CB_SUBS_MENU = "SUBS_MENU";
    private static final String CB_SUB_OPEN_PREFIX = "SUB_OPEN:";
    private static final String CB_SUB_UNSUB_ALL_PREFIX = "SUB_ALL:";
    private static final String CB_SUB_TOGGLE_PREFIX = "SUB_TOGGLE:";
    private static final String CB_UNSUB = "UNSUB";
    private static final String CB_NOOP = "NOOP";


    private final SessionCache sessionCache;
    private final TelegramBot telegramBot;
    private final ProductCardCache productCardCache;
    private final SubscriptionService subscriptionService;
    private final ScrapingExecutor scrapingExecutor;
    private final AdminCommandHandler adminCommandHandler;

    @PostConstruct
    public void init() {
        this.telegramBot.setUpdatesListener(this::onUpdates);
    }

    private int onUpdates(final List<Update> updates) {
        for (final var u : updates) {
            if (u.callbackQuery() != null) {
                handleCallback(u.callbackQuery());
                continue;
            }
            if (u.message() != null && u.message().text() != null) {
                final var chatId = u.message().chat().id();
                final var messageText = u.message().text();


                handleMessage(chatId, messageText.trim());
            }
        }

        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private ReplyKeyboardMarkup mainMenuKeyboard() {
        return new ReplyKeyboardMarkup(
                new KeyboardButton("📌 Подписки"),
                new KeyboardButton("ℹ️ Помощь")
        )
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .selective(true);
    }


    private void handleMessage(long chatId, String text) {
        final var adminReply = this.adminCommandHandler.tryHandle(chatId, text);
        if (adminReply.isPresent()) {
            send(chatId, adminReply.get());
            return;
        }

        if (text.equalsIgnoreCase("📌 Подписки")) {
            openSubscriptionsMenu(chatId);
            return;
        }

        if (text.equalsIgnoreCase("ℹ️ Помощь")) {
            send(
                    chatId,
                    """
                            Пришли ссылку Zara — покажу размеры и дам кнопки для выбора.
                            Пример:
                            https://www.zara.com/...
                            """,
                    null
            );
            return;
        }

        if (text.equalsIgnoreCase("/start") || text.equalsIgnoreCase("/help")) {
            send(
                    chatId,
                    """
                            Привет! 🙂
                            Пришли ссылку Zara — покажу размеры и дам кнопки для выбора.
                            Также можно открыть меню подписок кнопкой: 📌 Подписки
                            """,
                    null
            );
            return;
        }

        final var link = extractLink(text);
        if (link == null || !looksLikeZaraLink(link)) {
            send(chatId, "Пришли ссылку на товар Zara.", null);
            return;
        }

        send(chatId, "⏳ Получаю информацию о товаре...", null);
        this.scrapingExecutor.execute(() -> presentProductCard(chatId, link));
    }

    private void presentProductCard(final long chatId, final String link) {
        try {
            final var productKey = extractProductId(link);
            log.info("Loading product card for productKey {} from link {}.", productKey, link);

            final var card = this.productCardCache.getOrLoad(productKey, link);
            if (card == null) {
                send(chatId, "Не удалось получить карточку товара. Попробуй ещё раз.", null);
                return;
            }
            log.info("Product card for productKey {} loaded: {}.", productKey, card.getName());

            final var session = this.sessionCache.getOrCreate(chatId);

            session.setLastCard(card);
            session.getSelectedSizes().clear();
            session.setTrackWholeProduct(false);

            final var textCard = formatCard(card);

            if (isProductUnavailable(card)) {
                send(
                        chatId,
                        textCard
                                + "\n\n❗ Товар сейчас недоступен целиком (OUT OF STOCK / VIEW SIMILAR)."
                                + "\nЯ могу отслеживать появление товара в целом, даже если размеры сейчас не отдаются."
                                + "\n\nХотите начать отслеживать?",
                        trackWholeKeyboard()
                );
                return;
            }

            if (sizesWithAvailability(card).isEmpty()) {
                send(chatId, textCard + "\n\n❓ Не удалось получить размеры товара. Попробуй ещё раз позже.", null);
                return;
            }

            send(
                    chatId,
                    textCard + "\n\nМожно отслеживать любой размер: 🔴 — ждём появления, 🟢 — следим за ценой."
                            + "\n\nХотите начать отслеживать? Выберите:",
                    trackOrCancelKeyboard()
            );
        } catch (Exception e) {
            send(chatId, "Ошибка при получении товара: " + safe(e), null);
        }
    }

    private void handleCallback(final CallbackQuery cq) {
        final var chatId = cq.message().chat().id();
        final var messageId = cq.message().messageId();
        final var data = cq.data();


        final var session = this.sessionCache.getOrCreate(chatId);

        if (CB_SUBS_MENU.equals(data)) {
            final var subs = this.subscriptionService.getAllSubscribedSizes(chatId);
            if (subs.isEmpty()) {
                editText(chatId, messageId, "У вас пока нет активных подписок 🙂", null);
                return;
            }

            editText(
                    chatId,
                    messageId,
                    formatSubscriptionsList(chatId, subs),
                    subscriptionsListKeyboard(subs)
            );

            return;
        }

        if (data != null && data.startsWith(CB_SUB_OPEN_PREFIX)) {
            final var productKey = data.substring(CB_SUB_OPEN_PREFIX.length());
            if (productKey.isBlank()) {
                editText(chatId, messageId, "Не удалось открыть подписку. Откройте подписки заново.", null);
                return;
            }
            openSubscriptionDetails(chatId, messageId, productKey);
            return;
        }

        if (data != null && data.startsWith(CB_SUB_UNSUB_ALL_PREFIX)) {
            final var productKey = data.substring(CB_SUB_UNSUB_ALL_PREFIX.length());
            if (productKey.isBlank()) {
                editText(chatId, messageId, "Не удалось изменить подписку. Откройте подписки заново.", null);
                return;
            }

            final var sizes = this.subscriptionService.getSubscribedSizes(chatId, productKey);
            if (sizes.isEmpty()) {
                editText(chatId, messageId, "Подписка уже пустая. Откройте /subs заново.", null);
                return;
            }

            this.subscriptionService.unsubscribe(chatId, productKey, new HashSet<>(sizes), USER_ACTION);

            final var subs = this.subscriptionService.getAllSubscribedSizes(chatId);
            if (subs.isEmpty()) {
                editText(chatId, messageId, "✅ Готово! Подписка на товар удалена 🙂", null);
                return;
            }

            editText(
                    chatId,
                    messageId,
                    formatSubscriptionsList(chatId, subs),
                    subscriptionsListKeyboard(subs)
            );

            return;
        }

        if (data != null && data.startsWith(CB_SUB_TOGGLE_PREFIX)) {
            final var payload = data.substring(CB_SUB_TOGGLE_PREFIX.length());
            final var idx = payload.indexOf(':');
            if (idx <= 0 || idx == payload.length() - 1) {
                editText(chatId, messageId, "Не удалось изменить подписку. Откройте подписки заново.", null);
                return;
            }
            final var productKey = payload.substring(0, idx);
            final var size = normalizeSize(payload.substring(idx + 1));

            this.subscriptionService.unsubscribe(chatId, productKey, Set.of(size), USER_ACTION);

            openSubscriptionDetails(chatId, messageId, productKey);
            return;
        }

        if (data != null && data.startsWith(UserNotifier.CB_WHOLE_KEEP_PREFIX)) {
            final var productKey = data.substring(UserNotifier.CB_WHOLE_KEEP_PREFIX.length());
            answerToast(cq, "⏳ Проверяю отсутствующие размеры…");
            removeItemButtons(cq, Set.of(data));
            this.scrapingExecutor.execute(() -> keepMonitoringMissingSizes(chatId, productKey));
            return;
        }

        if (data != null && data.startsWith(UserNotifier.CB_SIZE_WATCH_PREFIX)) {
            handleSizeDecision(cq, data, UserNotifier.CB_SIZE_WATCH_PREFIX);
            return;
        }

        if (data != null && data.startsWith(UserNotifier.CB_SIZE_AWAIT_PREFIX)) {
            handleSizeDecision(cq, data, UserNotifier.CB_SIZE_AWAIT_PREFIX);
            return;
        }

        if (data != null && data.startsWith(UserNotifier.CB_SIZE_STOP_PREFIX)) {
            handleSizeDecision(cq, data, UserNotifier.CB_SIZE_STOP_PREFIX);
            return;
        }

        if (CB_NOOP.equals(data)) {
            return;
        }

        final var card = session.getLastCard();
        if (card == null) {
            editText(chatId, messageId, "Контекст устарел. Откройте товар заново.", null);
            return;
        }

        if (CB_CANCEL.equals(data)) {
            session.getSelectedSizes().clear();
            editText(chatId, messageId, formatCard(card) + "\n\nОк, не отслеживаем.", null);
            return;
        }

        if (CB_TRACK.equals(data)) {
            final var canPickSizes = !sizesWithAvailability(card).isEmpty();

            if (isProductUnavailable(card) && !canPickSizes) {
                session.getSelectedSizes().clear();
                session.setTrackWholeProduct(true);

                editText(
                        chatId,
                        messageId,
                        formatCard(card)
                                + "\n\n❗ Сейчас Zara не отдаёт корректную линейку размеров."
                                + "\nМогу отслеживать появление товара целиком (без выбора размеров).",
                        trackWholeKeyboard()
                );
                return;
            }

            session.setTrackWholeProduct(false);

            final var subscription = this.subscriptionService.getSubscribedSizes(chatId, card.getProductKey());

            session.getSelectedSizes().clear();
            session.getSelectedSizes().addAll(subscription);

            editText(
                    chatId,
                    messageId,
                    formatCard(card) + "\n\nВыбери размеры для отслеживания (можно несколько):",
                    sizesKeyboard(card, session.getSelectedSizes())
            );
            return;
        }

        if (CB_TRACK_WHOLE.equals(data)) {
            final var productKey = card.getProductKey();
            final var req = Set.of(WHOLE.getSize());
            final var diff = diffToSubscribe(chatId, productKey, req);

            if (diff.toAdd().isEmpty()) {
                editText(
                        chatId,
                        messageId,
                        "ℹ️ Вы уже отслеживаете этот товар целиком:\n\n" + card.getName(),
                        null
                );
                return;
            }

            this.subscriptionService.subscribe(chatId, card, diff.toAdd());

            session.getSelectedSizes().clear();
            session.setTrackWholeProduct(true);

            editText(
                    chatId,
                    messageId,
                    "✅ Принято! Буду отслеживать появление товара целиком:\n\n" + card.getName(),
                    null
            );

            return;
        }

        if (data != null && data.startsWith(CB_TOGGLE_PREFIX)) {
            final var size = normalizeSize(data.substring(CB_TOGGLE_PREFIX.length()));

            toggleSize(session.getSelectedSizes(), size);

            telegramBot.execute(
                    new EditMessageReplyMarkup(chatId, messageId)
                            .replyMarkup(sizesKeyboard(card, session.getSelectedSizes()))
            );

            return;
        }

        if (CB_CONFIRM.equals(data)) {
            final var productKey = card.getProductKey();
            final var requested = new HashSet<>(session.getSelectedSizes());

            final var existing = this.subscriptionService.getSubscribedSizes(chatId, productKey);
            if (existing.contains(WHOLE.getSize())) {
                this.subscriptionService.unsubscribe(chatId, productKey, Set.of(WHOLE.getSize()), USER_ACTION);
            }

            final var diff = diffToSubscribe(chatId, productKey, requested);

            if (diff.toAdd().isEmpty()) {
                editText(
                        chatId,
                        messageId,
                        "ℹ️ Эти размеры уже отслеживаются: " + formatSizes(diff.already())
                                + "\n\n" + card.getName(),
                        null
                );

                return;
            }

            this.subscriptionService.subscribe(chatId, card, diff.toAdd());

            session.getSelectedSizes().clear();
            session.setTrackWholeProduct(false);

            final var confirmation = new StringBuilder("✅ Принято!\n").append(formatWatchPlan(card, diff.toAdd()));
            if (!diff.already().isEmpty()) {
                confirmation.append("\nℹ️ Уже отслеживались: ").append(formatSizes(diff.already()));
            }
            confirmation.append("\n\n").append(card.getName());
            editText(chatId, messageId, confirmation.toString(), null);

            return;
        }

        if (CB_UNSUB.equals(data)) {
            editText(
                    chatId,
                    messageId,
                    "✅ Подписка на товар была отменена.",
                    null
            );

            this.subscriptionService.unsubscribeAll(chatId, card.getProductKey(), USER_ACTION);

            session.getSelectedSizes().clear();
            session.setTrackWholeProduct(false);
        }
    }

    /**
     * Continues monitoring after "the product is back in stock": re-reads the card
     * (invalidating first — the cached one may be stale) and subscribes the chat
     * to every size that is still out of stock.
     */
    private void keepMonitoringMissingSizes(final long chatId, final String productKey) {
        try {
            final var ref = this.subscriptionService.findProductRef(productKey);
            if (ref == null || ref.link() == null) {
                send(chatId, "Не нашёл ссылку на товар. Пришлите её заново — настрою отслеживание.", null);
                return;
            }

            this.productCardCache.invalidate(productKey);
            final var card = this.productCardCache.getOrLoad(productKey, ref.link());
            if (card == null) {
                send(chatId, "Не удалось перечитать карточку товара. Попробуйте прислать ссылку заново.", null);
                return;
            }

            final var missing = outOfStockSizes(card);
            if (missing.isEmpty()) {
                send(chatId, "✅ Сейчас все размеры в наличии — отслеживание не нужно 🙂", null);
                return;
            }

            this.subscriptionService.subscribe(chatId, card, missing);
            send(
                    chatId,
                    "✅ Продолжаю отслеживать размеры: " + missing + "\n\n" + card.getName(),
                    null
            );
        } catch (final Exception e) {
            send(chatId, "Ошибка при настройке отслеживания: " + safe(e), null);
        }
    }

    /**
     * Handles the Yes/No buttons attached to "size appeared" / "size sold out" notifications,
     * which now live inside a consolidated multi-item report. Payload after the prefix is
     * "productKey:size". Confirmation is a transient toast (answerCallbackQuery) and only this
     * item's own buttons are dropped from the report — the rest of it stays intact.
     */
    private void handleSizeDecision(final CallbackQuery cq, final String data, final String prefix) {
        final var chatId = cq.message().chat().id();
        final var payload = data.substring(prefix.length());
        final var idx = payload.indexOf(':');
        if (idx <= 0 || idx == payload.length() - 1) {
            answerToast(cq, "Контекст устарел. Откройте товар заново.");
            return;
        }
        final var productKey = payload.substring(0, idx);
        final var size = payload.substring(idx + 1);

        final String toast;
        switch (prefix) {
            case UserNotifier.CB_SIZE_WATCH_PREFIX -> toast =
                    this.subscriptionService.watchInStock(chatId, productKey, size)
                            ? "✅ Слежу за размером " + size + ": сообщу об изменении цены или если снова пропадёт."
                            : "Не нашёл эту подписку. Пришлите ссылку на товар заново.";
            case UserNotifier.CB_SIZE_AWAIT_PREFIX -> toast =
                    this.subscriptionService.awaitRestock(chatId, productKey, size)
                            ? "✅ Буду ждать появления размера " + size + " и сообщу."
                            : "Не нашёл эту подписку. Пришлите ссылку на товар заново.";
            default -> {
                this.subscriptionService.unsubscribe(chatId, productKey, Set.of(size), USER_ACTION);
                toast = "Ок, больше не слежу за размером " + size + ".";
            }
        }

        answerToast(cq, toast);
        removeItemButtons(cq, Set.of(
                UserNotifier.CB_SIZE_WATCH_PREFIX + payload,
                UserNotifier.CB_SIZE_AWAIT_PREFIX + payload,
                UserNotifier.CB_SIZE_STOP_PREFIX + payload
        ));
    }

    /**
     * Shows a transient popup on the callback button. Also stops Telegram's spinner on the button.
     */
    private void answerToast(final CallbackQuery cq, final String text) {
        this.telegramBot.execute(new AnswerCallbackQuery(cq.id()).text(text));
    }

    /**
     * Rebuilds the report's inline keyboard without the buttons whose callback data is in
     * {@code datasToRemove}, leaving every other item's buttons in place. No-op when the callback
     * message carries no keyboard (e.g. a legacy single-item notification).
     */
    private void removeItemButtons(final CallbackQuery cq, final Set<String> datasToRemove) {
        final var message = cq.message();
        final var markup = (message != null) ? message.replyMarkup() : null;
        if (markup == null || markup.inlineKeyboard() == null) {
            return;
        }

        this.telegramBot.execute(new EditMessageReplyMarkup(message.chat().id(), message.messageId())
                .replyMarkup(filterOutButtons(markup, datasToRemove)));
    }

    /**
     * Returns a copy of {@code markup} without the buttons whose callback data is in
     * {@code datasToRemove}, dropping any row left empty. Extracted (and package-private) so the
     * filtering can be unit-tested without a live Telegram callback.
     */
    static InlineKeyboardMarkup filterOutButtons(final InlineKeyboardMarkup markup, final Set<String> datasToRemove) {
        final var rebuilt = new InlineKeyboardMarkup();
        if (markup == null || markup.inlineKeyboard() == null) {
            return rebuilt;
        }
        for (final var row : markup.inlineKeyboard()) {
            final var kept = Arrays.stream(row)
                    .filter(b -> b.callbackData() == null || !datasToRemove.contains(b.callbackData()))
                    .toArray(InlineKeyboardButton[]::new);
            if (kept.length > 0) {
                rebuilt.addRow(kept);
            }
        }
        return rebuilt;
    }

    private void openSubscriptionsMenu(long chatId) {
        final var subs = this.subscriptionService.getAllSubscribedSizes(chatId);
        if (subs.isEmpty()) {
            send(chatId, "У вас пока нет активных подписок 🙂", null);
            return;
        }

        send(
                chatId,
                formatSubscriptionsList(chatId, subs),
                subscriptionsListKeyboard(subs)
        );
    }

    private String formatSubscriptionsList(final long chatId, final Map<String, Set<String>> subs) {
        final var sb = new StringBuilder("📌 Ваши подписки:\n\n");

        var anyWatched = false;
        int i = 1;
        for (final var entry : subs.entrySet()) {
            final var modes = this.subscriptionService.getSubscribedSizeModes(chatId, entry.getKey());
            anyWatched |= modes.containsValue(WATCH_IN_STOCK);
            sb.append(i++).append(") ").append(productTitle(entry.getKey())).append("\n");
            sb.append("   • размеры: ").append(sizesWithMarkers(entry.getValue(), modes)).append("\n\n");
        }

        if (anyWatched) {
            sb.append("💰 — слежу за ценой (размер уже в наличии)\n\n");
        }
        sb.append("Выберите подписку, чтобы управлять ею.");
        return sb.toString();
    }

    /**
     * Renders sizes as "[S 💰, M]", flagging the ones being watched in stock for price changes.
     */
    private String sizesWithMarkers(final Set<String> sizes, final Map<String, SubscriptionMode> modes) {
        return sizes.stream()
                .sorted()
                .map(s -> normalizeSize(s) + (modes.get(s) == WATCH_IN_STOCK ? " 💰" : ""))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * The subscriptions list. Each button carries the productKey (the short "-p&lt;digits&gt;" id)
     * directly in its callback data — no ephemeral session token — so it still resolves after a bot
     * restart or session eviction.
     */
    private InlineKeyboardMarkup subscriptionsListKeyboard(final Map<String, Set<String>> subs) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();

        for (final var productKey : subs.keySet()) {
            final var title = productTitle(productKey);
            final var shortTitle = title.length() > 32 ? title.substring(0, 32) + "…" : title;

            kb.addRow(
                    new InlineKeyboardButton(shortTitle).callbackData(CB_SUB_OPEN_PREFIX + productKey)
            );
        }

        kb.addRow(new InlineKeyboardButton("↩ Закрыть").callbackData(CB_BACK));
        return kb;
    }

    private void openSubscriptionDetails(long chatId, int messageId, String productKey) {
        final var sizes = this.subscriptionService.getSubscribedSizes(chatId, productKey);
        if (sizes.isEmpty()) {
            editText(chatId, messageId, "Подписка уже неактуальна (пусто). Откройте подписки заново.", null);
            return;
        }

        final var ref = this.subscriptionService.getProductRef(productKey);
        final var modes = this.subscriptionService.getSubscribedSizeModes(chatId, productKey);

        final var sb = new StringBuilder();
        sb.append("🧾 ").append(productTitle(productKey)).append("\n");
        if (ref != null && ref.link() != null) sb.append(ref.link()).append("\n");
        sb.append("\n📌 Отслеживаемые размеры:\n");
        sizes.forEach(s -> sb.append("• ").append(normalizeSize(s))
                .append(modes.get(s) == WATCH_IN_STOCK ? " 💰" : "").append("\n"));
        if (modes.containsValue(WATCH_IN_STOCK)) {
            sb.append("\n💰 — слежу за ценой (размер уже в наличии)");
        }
        sb.append("\nНажмите на размер, чтобы отменить отслеживание.");

        editText(
                chatId,
                messageId,
                sb.toString(),
                subscriptionDetailsKeyboard(productKey, sizes, modes)
        );
    }

    private String productTitle(final String productKey) {
        final var ref = this.subscriptionService.getProductRef(productKey);
        return (ref != null && ref.name() != null) ? ref.name() : productKey;
    }

    /**
     * The per-product details keyboard. Size-toggle buttons carry a stateless
     * {@code productKey:size} payload, so they keep working after a restart or session eviction.
     */
    private InlineKeyboardMarkup subscriptionDetailsKeyboard(
            String productKey, Set<String> sizes, Map<String, SubscriptionMode> modes) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> row = new ArrayList<>(3);
        for (String size : sizes.stream().sorted().toList()) {
            final var label = normalizeSize(size) + (modes.get(size) == WATCH_IN_STOCK ? " 💰" : "");
            row.add(new InlineKeyboardButton(label)
                    .callbackData(CB_SUB_TOGGLE_PREFIX + productKey + ":" + normalizeSize(size)));
            if (row.size() == 3) {
                kb.addRow(row.toArray(new InlineKeyboardButton[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) kb.addRow(row.toArray(new InlineKeyboardButton[0]));

        kb.addRow(new InlineKeyboardButton("🛑 Отписаться от всех размеров")
                .callbackData(CB_SUB_UNSUB_ALL_PREFIX + productKey));

        kb.addRow(new InlineKeyboardButton("↩ Назад к списку").callbackData(CB_SUBS_MENU));
        return kb;
    }

    private InlineKeyboardMarkup trackOrCancelKeyboard() {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton("✅ Отслеживать").callbackData(CB_TRACK),
                new InlineKeyboardButton("❌ Не надо").callbackData(CB_CANCEL)
        );
    }

    private InlineKeyboardMarkup trackWholeKeyboard() {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton("✅ Отслеживать товар целиком").callbackData(CB_TRACK_WHOLE),
                new InlineKeyboardButton("↩ Назад").callbackData(CB_BACK)
        );
    }

    private InlineKeyboardMarkup sizesKeyboard(ProductCard card, Set<String> selected) {
        final var sizes = sizesWithAvailability(card);

        List<InlineKeyboardButton[]> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>(3);

        for (final var entry : sizes.entrySet()) {
            final var size = entry.getKey();
            final var marker = entry.getValue() ? "🟢" : "🔴";
            final var picked = selected.contains(size);
            final var label = (picked ? "✅ " : "") + size + " " + marker;

            currentRow.add(new InlineKeyboardButton(label).callbackData(CB_TOGGLE_PREFIX + size));

            if (currentRow.size() == 3) {
                rows.add(currentRow.toArray(new InlineKeyboardButton[0]));
                currentRow.clear();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow.toArray(new InlineKeyboardButton[0]));
        }

        rows.add(new InlineKeyboardButton[]{
                new InlineKeyboardButton("✅ Подтвердить").callbackData(CB_CONFIRM),
                new InlineKeyboardButton("↩ Назад").callbackData(CB_BACK)
        });

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        for (InlineKeyboardButton[] r : rows) kb.addRow(r);
        return kb;
    }

    private void toggleSize(Set<String> selected, String size) {
        if (selected.contains(size)) selected.remove(size);
        else selected.add(size);
    }


    private void send(long chatId, String text) {
        telegramBot.execute(new SendMessage(chatId, text).replyMarkup(mainMenuKeyboard()));
    }

    private void send(long chatId, String text, InlineKeyboardMarkup kb) {
        SendMessage msg = new SendMessage(chatId, text);
        if (kb != null) msg.replyMarkup(kb);
        else msg.replyMarkup(mainMenuKeyboard());
        telegramBot.execute(msg);
    }


    private void editText(long chatId, int messageId, String text, InlineKeyboardMarkup kb) {
        EditMessageText edit = new EditMessageText(chatId, messageId, text);
        if (kb != null) edit.replyMarkup(kb);
        telegramBot.execute(edit);
    }

    private String extractLink(final String text) {
        if (text.startsWith("/check")) {
            String[] parts = text.split("\\s+", 2);
            return parts.length == 2 ? parts[1].trim() : null;
        }
        return text.startsWith("http") ? text : null;
    }

    private boolean looksLikeZaraLink(String link) {
        String l = link.toLowerCase();
        return l.contains("zara.com") && l.contains("-p");
    }

    private String formatCard(ProductCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧾 ").append(card.getName()).append("\n");
        sb.append(card.getLink()).append("\n");
        if (card.getPrice() != null) {
            sb.append("💶 Цена: ").append(card.getPrice().formatted()).append("\n");
        }
        sb.append("\n");

        final var sizes = sizesWithAvailability(card);
        if (sizes.isEmpty()) {
            return sb.append("Размеры не найдены.").toString();
        }

        sb.append("Размеры:\n");
        sizes.forEach((size, available) -> sb.append("• ").append(size).append(" — ")
                .append(available ? "🟢 в наличии" : "🔴 нет в наличии").append("\n"));

        return sb.toString();
    }

    /**
     * The full size lineup as a sorted {@code normalizedSize → inStock} map, excluding the
     * WHOLE "*" sentinel. Drives both the card text and the size picker.
     */
    private Map<String, Boolean> sizesWithAvailability(final ProductCard card) {
        final var result = new LinkedHashMap<String, Boolean>();
        if (card == null || card.getSizeDetails() == null) {
            return result;
        }
        card.getSizeDetails().stream()
                .filter(s -> s.getSize() != null && !s.getSize().isBlank())
                .filter(s -> !WHOLE.getSize().equals(s.getSize().trim()))
                .sorted(Comparator.comparing(s -> normalizeSize(s.getSize())))
                .forEach(s -> result.put(normalizeSize(s.getSize()), s.isSizeAvailability()));
        return result;
    }

    /**
     * Human-readable plan for the sizes just subscribed, grouped by what the bot will do:
     * out-of-stock → wait for a restock; in-stock → watch price / sell-out.
     */
    private String formatWatchPlan(final ProductCard card, final Set<String> sizes) {
        final var availability = sizesWithAvailability(card);
        final var awaited = new java.util.TreeSet<String>();
        final var watched = new java.util.TreeSet<String>();
        for (final var size : sizes) {
            if (Boolean.TRUE.equals(availability.get(normalizeSize(size)))) {
                watched.add(normalizeSize(size));
            } else {
                awaited.add(normalizeSize(size));
            }
        }

        final var sb = new StringBuilder();
        if (!awaited.isEmpty()) {
            sb.append("⏳ Жду появления: ").append(awaited).append("\n");
        }
        if (!watched.isEmpty()) {
            sb.append("💰 Слежу за ценой (уже в наличии): ").append(watched).append("\n");
        }
        return sb.toString();
    }

    private boolean isProductUnavailable(final ProductCard card) {
        if (card == null || card.getSizeDetails() == null) {
            return false;
        }

        return card.getSizeDetails()
                .stream()
                .anyMatch(s -> !s.isSizeAvailability() && s.getSize().equals(WHOLE.getSize()));
    }

    private Set<String> outOfStockSizes(ProductCard card) {
        if (card == null || card.getSizeDetails() == null) {
            return Set.of();
        }

        return card.getSizeDetails().stream()
                .filter(s -> !s.isSizeAvailability())
                .map(this::sizeToString)
                .filter(s -> !s.equals("?"))
                .map(this::normalizeSize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String sizeToString(SizeInfo s) {
        if (s.getSize() != null && !s.getSize().isBlank()) {
            return s.getSize().trim();
        }

        return "?";
    }

    private String normalizeSize(String s) {
        return s.replaceAll("\\s+", "").toUpperCase();
    }

    private String safe(Exception e) {
        final var m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    private SubscribeDiff diffToSubscribe(
            final long chatId,
            final String productKey,
            final Set<String> requested
    ) {
        final var existing = this.subscriptionService.getSubscribedSizes(chatId, productKey);
        final var already = new HashSet<String>();
        final var toAdd = new HashSet<String>();

        if (requested == null) {
            return new SubscribeDiff(Set.of(), Set.of());
        }

        for (final var s : requested) {
            if (s == null) {
                continue;
            }

            if (existing.contains(s)) {
                already.add(s);
            } else {
                toAdd.add(s);
            }
        }

        return new SubscribeDiff(toAdd, already);
    }

    private String formatSizes(final Set<String> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return "[]";
        }

        return sizes.toString();
    }

    private record SubscribeDiff(Set<String> toAdd, Set<String> already) {
    }
}
