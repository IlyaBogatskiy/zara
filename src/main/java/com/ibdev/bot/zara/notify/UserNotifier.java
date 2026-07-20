package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.notify.NotifyEvent.PriceMoved;
import com.ibdev.bot.zara.notify.NotifyEvent.SizeAppeared;
import com.ibdev.bot.zara.notify.NotifyEvent.SizeSoldOut;
import com.ibdev.bot.zara.notify.NotifyEvent.WholeAvailable;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class UserNotifier {

    /**
     * The "keep monitoring missing sizes" button — handled by the telegram layer.
     */
    public static final String CB_WHOLE_KEEP_PREFIX = "WHOLE_KEEP:";

    /**
     * "size appeared → keep watching its price & availability" — payload: productKey:size.
     */
    public static final String CB_SIZE_WATCH_PREFIX = "SIZE_WATCH:";

    /**
     * "size sold out → keep waiting for it to reappear" — payload: productKey:size.
     */
    public static final String CB_SIZE_AWAIT_PREFIX = "SIZE_AWAIT:";

    /**
     * "stop watching this size" (used by both prompts) — payload: productKey:size.
     */
    public static final String CB_SIZE_STOP_PREFIX = "SIZE_STOP:";

    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final int MAX_RETRY_AFTER_SECONDS = 30;
    private static final int BUTTON_NAME_HINT_LENGTH = 16;

    private final TelegramBot telegramBot;
    private final AdminNotifier adminNotifier;

    /**
     * Sends one consolidated report to a chat for everything that changed this tick.
     * The events are grouped by product; each actionable event contributes a labelled button row,
     * each of which the telegram layer resolves via the unchanged {@code productKey:size} payloads.
     */
    public void sendReport(final long chatId, final List<NotifyEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        final var byProduct = new LinkedHashMap<String, List<NotifyEvent>>();
        for (final var event : events) {
            byProduct.computeIfAbsent(event.productKey(), key -> new ArrayList<>()).add(event);
        }

        final var text = new StringBuilder("🔔 Обновления по отслеживаемым товарам:\n");
        final var keyboard = new InlineKeyboardMarkup();
        var hasButtons = false;

        for (final var group : byProduct.values()) {
            final var name = group.getFirst().productName();
            final var link = group.getFirst().link();

            text.append("\n📦 ").append(name).append("\n");
            if (link != null && !link.isBlank()) {
                text.append(link).append("\n");
            }
            for (final var event : group) {
                text.append(renderLine(event)).append("\n");
                hasButtons |= appendButtons(keyboard, event, name);
            }
        }

        final var message = new SendMessage(chatId, text.toString());
        if (hasButtons) {
            message.replyMarkup(keyboard);
        }
        execute(chatId, message);
    }

    private String renderLine(final NotifyEvent event) {
        return switch (event) {
            case SizeAppeared s -> "✅ Размер " + s.size() + " появился в наличии"
                    + (s.lowStock() ? "\n⚠️ Мало осталось — могут разобрать быстро" : "");
            case SizeSoldOut s -> "⚠️ Размер " + s.size() + " снова пропал из наличия";
            case PriceMoved p -> (p.newPrice().amount() < p.oldPrice().amount()
                    ? "📉 Цена снизилась: " : "📈 Цена выросла: ")
                    + p.oldPrice().formatted() + " → " + p.newPrice().formatted();
            case WholeAvailable w -> renderWhole(w);
        };
    }

    private String renderWhole(final WholeAvailable w) {
        final var sb = new StringBuilder("✅ Товар появился в наличии!");
        if (!w.availableSizes().isEmpty()) {
            sb.append("\n✅ Размеры в наличии: ").append(w.availableSizes());
        }
        if (w.unavailableSizes().isEmpty()) {
            sb.append("\nℹ️ Я остановил мониторинг товара.");
        } else {
            sb.append("\n❌ Размеры не в наличии: ").append(w.unavailableSizes())
                    .append("\nℹ️ Мониторинг товара целиком остановлен. ")
                    .append("Могу продолжить следить за отсутствующими размерами — кнопка ниже.");
        }
        return sb.toString();
    }

    /**
     * @return whether the event contributed any button.
     */
    private boolean appendButtons(final InlineKeyboardMarkup keyboard, final NotifyEvent event, final String name) {
        final var hint = shortName(name);
        return switch (event) {
            case SizeAppeared s -> {
                final var payload = s.productKey() + ":" + s.size();
                keyboard.addRow(
                        new InlineKeyboardButton("✅ Следить " + s.size() + " · " + hint)
                                .callbackData(CB_SIZE_WATCH_PREFIX + payload),
                        new InlineKeyboardButton("❌ Стоп " + s.size() + " · " + hint)
                                .callbackData(CB_SIZE_STOP_PREFIX + payload)
                );
                yield true;
            }
            case SizeSoldOut s -> {
                final var payload = s.productKey() + ":" + s.size();
                keyboard.addRow(
                        new InlineKeyboardButton("✅ Ждать " + s.size() + " · " + hint)
                                .callbackData(CB_SIZE_AWAIT_PREFIX + payload),
                        new InlineKeyboardButton("❌ Стоп " + s.size() + " · " + hint)
                                .callbackData(CB_SIZE_STOP_PREFIX + payload)
                );
                yield true;
            }
            case WholeAvailable w -> {
                if (w.unavailableSizes().isEmpty()) {
                    yield false;
                }
                keyboard.addRow(new InlineKeyboardButton("📌 Отслеживать отсутствующие · " + hint)
                        .callbackData(CB_WHOLE_KEEP_PREFIX + w.productKey()));
                yield true;
            }
            case PriceMoved ignored -> false;
        };
    }

    private String shortName(final String name) {
        if (name == null) {
            return "";
        }
        return name.length() > BUTTON_NAME_HINT_LENGTH ? name.substring(0, BUTTON_NAME_HINT_LENGTH) + "…" : name;
    }

    /**
     * Sends a message, checking the Telegram response and retrying on rate-limit (429).
     * A dropped notification used to be invisible — the response was never inspected.
     */
    private void execute(final long chatId, final SendMessage message) {
        for (var attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            final BaseResponse response;
            try {
                response = this.telegramBot.execute(message);
            } catch (final Exception e) {
                log.warn("Telegram send to chat {} threw: {}", chatId, e.getMessage());
                reportDeliveryFailure(chatId, "исключение: " + e.getMessage());
                return;
            }

            if (response == null || response.isOk()) {
                return;
            }

            if (response.errorCode() == 429) {
                final var params = response.parameters();
                final var retryAfter = (params != null && params.retryAfter() != null) ? params.retryAfter() : 1;
                final var waitMs = Math.min(retryAfter, MAX_RETRY_AFTER_SECONDS) * 1000L;
                log.warn("Telegram rate-limited chat {} (attempt {}/{}), retrying after {}s.",
                        chatId, attempt, MAX_SEND_ATTEMPTS, retryAfter);
                if (!sleep(waitMs)) {
                    return;
                }
                continue;
            }

            log.warn("Telegram send to chat {} failed: {} {}", chatId, response.errorCode(), response.description());
            reportDeliveryFailure(chatId, response.errorCode() + " " + response.description());
            return;
        }
        log.warn("Telegram send to chat {} giving up after {} attempts.", chatId, MAX_SEND_ATTEMPTS);
        reportDeliveryFailure(chatId, "rate-limit 429, сдался после " + MAX_SEND_ATTEMPTS + " попыток");
    }

    /**
     * A user report that could not be delivered is a silent loss — the user never learns a size came
     * back. Escalate to the admin chat (throttled by {@link AdminNotifier} under one key, so a
     * Telegram-wide outage is one alert, not one per chat).
     */
    private void reportDeliveryFailure(final long chatId, final String reason) {
        this.adminNotifier.alert("delivery-failed",
                "📵 Не удалось доставить оповещение в чат " + chatId + ": " + reason);
    }

    private boolean sleep(final long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
