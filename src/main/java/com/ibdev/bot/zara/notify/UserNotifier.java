package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.service.subscription.SubscriptionService.ProductRef;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.SendMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * User-facing monitoring notifications: texts and inline buttons live here so the
 * scheduler knows nothing about Telegram or the UI callback constants.
 *
 * @author i.bogatskii
 */
@Component
@RequiredArgsConstructor
public class UserNotifier {

    /** The "keep monitoring missing sizes" button — handled by the telegram layer. */
    public static final String CB_WHOLE_KEEP_PREFIX = "WHOLE_KEEP:";

    private final TelegramBot telegramBot;

    public void sizeAppeared(final long chatId, final String size, final String productName) {
        this.telegramBot.execute(new SendMessage(
                chatId,
                "✅ Размер " + size + " товара " + productName + " появился в наличии! " +
                        "Я остановил мониторинг этого размера."
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
