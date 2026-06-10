package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator alerts about technical problems (selector breakage and the like).
 * Always writes ERROR to the log; messages Telegram only when zara.admin-chat-id
 * is set, and at most once per 6 hours per problem key — no spam on every tick.
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class AdminNotifier {

    private static final long MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private final TelegramBot telegramBot;
    private final ZaraProperties properties;

    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    public void alert(final String key, final String message) {
        log.error("ALERT [{}]: {}", key, message);

        final var adminChatId = this.properties.getAdminChatId();
        if (adminChatId == null || adminChatId == 0) {
            return;
        }

        final var now = System.currentTimeMillis();
        final var last = this.lastAlertAt.get(key);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return;
        }
        this.lastAlertAt.put(key, now);

        try {
            this.telegramBot.execute(new SendMessage(adminChatId, "🔧 " + message));
        } catch (final Exception e) {
            log.error("Failed to deliver admin alert: {}", e.getMessage());
        }
    }
}
