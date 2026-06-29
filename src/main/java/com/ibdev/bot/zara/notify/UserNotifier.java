package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.ProductRef;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.SendMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * @author i.bogatskii
 */
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

    private final TelegramBot telegramBot;

    /**
     * The watched OOS size came back: ask whether to keep watching (now its price & availability).
     */
    public void sizeAppeared(final long chatId, final String productKey, final String size, final String productName) {
        final var payload = productKey + ":" + size;
        this.telegramBot.execute(new SendMessage(
                chatId,
                "✅ Размер " + size + " товара " + productName + " появился в наличии!\n\n"
                        + "Продолжить отслеживать этот размер? Если да — буду следить за ценой "
                        + "и сообщу, если размер снова пропадёт."
        ).replyMarkup(new InlineKeyboardMarkup(
                new InlineKeyboardButton("✅ Да, следить за ценой и наличием")
                        .callbackData(CB_SIZE_WATCH_PREFIX + payload),
                new InlineKeyboardButton("❌ Нет, остановить")
                        .callbackData(CB_SIZE_STOP_PREFIX + payload)
        )));
    }

    /**
     * A watched in-stock size sold out: ask whether to keep waiting for it to reappear.
     */
    public void sizeDisappeared(final long chatId, final String productKey, final String size, final String productName) {
        final var payload = productKey + ":" + size;
        this.telegramBot.execute(new SendMessage(
                chatId,
                "⚠️ Размер " + size + " товара " + productName + " снова пропал из наличия.\n\n"
                        + "Продолжить отслеживать появление этого размера?"
        ).replyMarkup(new InlineKeyboardMarkup(
                new InlineKeyboardButton("✅ Да, ждать появления")
                        .callbackData(CB_SIZE_AWAIT_PREFIX + payload),
                new InlineKeyboardButton("❌ Нет, остановить")
                        .callbackData(CB_SIZE_STOP_PREFIX + payload)
        )));
    }

    /**
     * Price moved while the user was watching the in-stock size.
     */
    public void priceChanged(
            final long chatId,
            final ProductRef ref,
            final PriceInfo oldPrice,
            final PriceInfo newPrice
    ) {
        final var dropped = newPrice.amount() < oldPrice.amount();
        final var headline = dropped ? "📉 Цена снизилась!" : "📈 Цена выросла!";
        this.telegramBot.execute(new SendMessage(
                chatId,
                headline + "\n" + ref.name() + "\n" + ref.link()
                        + "\n\nБыло: " + oldPrice.formatted()
                        + "\nСтало: " + newPrice.formatted()
        ));
    }

    public void wholeProductAvailable(
            final long chatId,
            final String productKey,
            final ProductRef ref,
            final Set<String> availableSizes,
            final Set<String> unavailableSizes
    ) {
        final var text = new StringBuilder()
                .append("✅ Товар появился в наличии!\n").append(ref.name()).append("\n").append(ref.link())
                .append("\n\n✅ Размеры в наличии: ").append(availableSizes);

        final SendMessage message;
        if (unavailableSizes.isEmpty()) {
            text.append("\n\nℹ️ Я остановил мониторинг товара.");
            message = new SendMessage(chatId, text.toString());
        } else {
            text.append("\n❌ Размеры не в наличии: ").append(unavailableSizes)
                    .append("\n\nℹ️ Мониторинг товара целиком остановлен. ")
                    .append("Могу продолжить следить за отсутствующими размерами:");
            message = new SendMessage(chatId, text.toString()).replyMarkup(new InlineKeyboardMarkup(
                    new InlineKeyboardButton("📌 Отслеживать отсутствующие")
                            .callbackData(CB_WHOLE_KEEP_PREFIX + productKey)
            ));
        }

        this.telegramBot.execute(message);
    }
}
