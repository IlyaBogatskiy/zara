package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;

/**
 * Pings the admin chat when the bot starts and stops, so deploys, restarts and crash loops are
 * visible (a burst of "started" pings is itself the crash-loop signal). The startup ping runs after
 * the warm-up/seed steps (higher {@code @Order}) so it can report how much was restored.
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class AdminLifecycleNotifier {

    private final SubscriptionService subscriptionService;
    private final AdminNotifier adminNotifier;

    @Order(20)
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        final var products = this.subscriptionService.activeProductKeys();
        final var chats = new HashSet<Long>();
        for (final var productKey : products) {
            chats.addAll(this.subscriptionService.getSubscribersByProduct(productKey).keySet());
        }
        this.adminNotifier.notice(String.format(
                "✅ Бот запущен: отслеживается %d товар(ов), %d чат(ов).", products.size(), chats.size()));
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        this.adminNotifier.notice("⏹ Бот останавливается.");
    }
}
