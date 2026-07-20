package com.ibdev.bot.zara.telegram;

import com.ibdev.bot.zara.client.PriceInfo;
import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService.Watch;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.ibdev.bot.zara.client.ClothingSizes.WHOLE;
import static com.ibdev.bot.zara.storage.model.SubscriptionMode.AWAIT_RESTOCK;

/**
 * Read-only operator commands exposed through the Telegram bot itself — the same stats the
 * (unauthenticated, local-only) OperatorController serves, but gated behind {@code zara.admin-chat-id}
 * so the operator can inspect who is monitoring what from their phone without opening any port. Every
 * command reads straight from {@link SubscriptionService}; none of them mutate anything.
 * <p>
 * {@link #tryHandle} returns empty for a non-admin chat, a blank message, or text that is not a known
 * admin command, so the listener falls through to the normal user flow — an admin sending a product
 * link still gets the normal card, only the {@code /stats}-family verbs are intercepted.
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class AdminCommandHandler {

    private static final int MAX_LEN = 3900;

    private final SubscriptionService subscriptionService;
    private final ZaraProperties properties;

    public Optional<String> tryHandle(final long chatId, final String text) {
        if (text == null || text.isBlank() || !isAdmin(chatId)) {
            return Optional.empty();
        }

        final var trimmed = text.trim();
        if (!trimmed.startsWith("/")) {
            return Optional.empty();
        }
        final var parts = trimmed.substring(1).split("\\s+", 2);
        final var command = parts[0].toLowerCase();
        final var argument = parts.length > 1 ? parts[1].trim() : "";

        return switch (command) {
            case "admin" -> Optional.of(help());
            case "stats" -> Optional.of(cap(overview()));
            case "products" -> Optional.of(cap(products()));
            case "product" -> Optional.of(cap(productDetails(argument)));
            case "chats" -> Optional.of(cap(chats()));
            case "chat" -> Optional.of(cap(chatDetails(argument)));
            default -> Optional.empty();
        };
    }

    private boolean isAdmin(final long chatId) {
        final var adminChatId = this.properties.getAdminChatId();
        return adminChatId != null && adminChatId != 0 && adminChatId == chatId;
    }

    private String help() {
        return """
                🛠 Админ-команды:
                /stats — сводка (товары, чаты, подписки)
                /products — список товаров и число подписчиков
                /product <ключ> — кто следит за товаром и за какими размерами
                /chats — список чатов и сколько товаров каждый отслеживает
                /chat <id> — что отслеживает конкретный чат""";
    }

    private String overview() {
        final var products = this.subscriptionService.activeProductKeys();
        final var chats = new java.util.HashSet<Long>();
        var sizes = 0;
        var awaiting = 0;
        var watching = 0;
        final var watchersByProduct = new LinkedHashMap<String, Integer>();
        for (final var key : products) {
            final var watches = this.subscriptionService.getActiveWatches(key);
            final var productChats = new java.util.HashSet<Long>();
            for (final var watch : watches) {
                chats.add(watch.chatId());
                productChats.add(watch.chatId());
                if (WHOLE.getSize().equals(watch.size())) {
                    continue;
                }
                sizes++;
                if (watch.mode() == AWAIT_RESTOCK) {
                    awaiting++;
                } else {
                    watching++;
                }
            }
            watchersByProduct.put(key, productChats.size());
        }

        final var body = new StringBuilder();
        body.append("📊 Статистика\n");
        body.append("Товаров в мониторинге: ").append(products.size()).append('\n');
        body.append("Уникальных чатов: ").append(chats.size()).append('\n');
        body.append("Подписок на размеры: ").append(sizes)
                .append(" (ждут рестока: ").append(awaiting)
                .append(", следят: ").append(watching).append(")\n");

        body.append("\nТоп по подписчикам:\n");
        watchersByProduct.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> body.append("• ").append(nameOf(e.getKey()))
                        .append(" [").append(e.getKey()).append("] — ").append(e.getValue()).append(" чат.\n"));
        return body.toString();
    }

    private String products() {
        final var keys = this.subscriptionService.activeProductKeys();
        if (keys.isEmpty()) {
            return "Нет активных товаров.";
        }
        final var body = new StringBuilder("📦 Товары (").append(keys.size()).append("):\n");
        for (final var key : keys) {
            final var subscribers = this.subscriptionService.getSubscribersByProduct(key);
            final var allSizes = new java.util.TreeSet<String>();
            subscribers.values().forEach(allSizes::addAll);
            body.append("• [").append(key).append("] ").append(nameOf(key))
                    .append(" — ").append(subscribers.size()).append(" чат., размеры: ")
                    .append(allSizes.isEmpty() ? "—" : String.join(", ", allSizes)).append('\n');
        }
        return body.toString();
    }

    private String productDetails(final String key) {
        if (key.isBlank()) {
            return "Укажи ключ товара: /product <ключ>";
        }
        final var watches = this.subscriptionService.getActiveWatches(key);
        if (watches.isEmpty()) {
            return "По товару " + key + " нет активных подписок.";
        }
        final var ref = this.subscriptionService.findProductRef(key);

        final var byChat = new TreeMap<Long, StringBuilder>();
        for (final var watch : watches) {
            byChat.computeIfAbsent(watch.chatId(), c -> new StringBuilder())
                    .append(watch.size()).append('(').append(modeLabel(watch)).append(") ");
        }

        final var body = new StringBuilder("📦 ").append(ref == null ? key : ref.name()).append('\n');
        if (ref != null) {
            body.append(ref.link()).append('\n');
        }
        body.append("Ключ: ").append(key).append('\n');
        final var price = this.subscriptionService.loadLastKnownPrices().get(key);
        body.append("Последняя цена: ").append(price == null ? "—" : price.formatted()).append('\n');
        body.append("Подписчики (").append(byChat.size()).append("):\n");
        byChat.forEach((chatId, sizes) -> body.append("• чат ").append(chatId).append(": ").append(sizes.toString().trim()).append('\n'));
        return body.toString();
    }

    private String chats() {
        final var counts = new TreeMap<Long, Integer>();
        for (final var key : this.subscriptionService.activeProductKeys()) {
            for (final var chatId : this.subscriptionService.getSubscribersByProduct(key).keySet()) {
                counts.merge(chatId, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "Нет активных чатов.";
        }
        final var body = new StringBuilder("👤 Чаты (").append(counts.size()).append("):\n");
        counts.forEach((chatId, n) -> body.append("• чат ").append(chatId).append(": ").append(n).append(" товар.\n"));
        return body.toString();
    }

    private String chatDetails(final String argument) {
        final long chatId;
        try {
            chatId = Long.parseLong(argument.trim());
        } catch (final NumberFormatException e) {
            return "Укажи id чата числом: /chat <id>";
        }
        final var subs = this.subscriptionService.getAllSubscribedSizes(chatId);
        if (subs.isEmpty()) {
            return "Чат " + chatId + " ничего не отслеживает.";
        }
        final var body = new StringBuilder("👤 Чат ").append(chatId).append('\n');
        body.append("Отслеживает товаров: ").append(subs.size()).append('\n');
        subs.forEach((key, sizes) -> {
            final var modes = this.subscriptionService.getSubscribedSizeModes(chatId, key);
            final var rendered = new StringBuilder();
            for (final var size : sizes) {
                rendered.append(size).append('(').append(modeLabel(modes.get(size))).append(") ");
            }
            body.append("• ").append(nameOf(key)).append(" [").append(key).append("]: ")
                    .append(rendered.toString().trim()).append('\n');
        });
        return body.toString();
    }

    private String nameOf(final String key) {
        final var ref = this.subscriptionService.findProductRef(key);
        return ref == null ? "?" : ref.name();
    }

    private String modeLabel(final Watch watch) {
        return modeLabel(watch.mode());
    }

    private String modeLabel(final com.ibdev.bot.zara.storage.model.SubscriptionMode mode) {
        if (mode == null) {
            return "?";
        }
        return mode == AWAIT_RESTOCK ? "ждёт" : "следит";
    }

    private String cap(final String text) {
        if (text.length() <= MAX_LEN) {
            return text;
        }
        return text.substring(0, MAX_LEN) + "\n…(обрезано)";
    }
}
