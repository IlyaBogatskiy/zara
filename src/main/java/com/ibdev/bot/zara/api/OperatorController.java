package com.ibdev.bot.zara.api;

import com.ibdev.bot.zara.cache.ProductCardCache;
import com.ibdev.bot.zara.scheduler.MonitoringScheduler;
import com.ibdev.bot.zara.scheduler.SelectorCanary;
import com.ibdev.bot.zara.client.ProductCard;
import com.ibdev.bot.zara.client.ProductSnapshot;
import com.ibdev.bot.zara.service.page.PageService;
import com.ibdev.bot.zara.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ibdev.bot.zara.storage.model.SubscriptionChangeReason.USER_ACTION;
import static com.ibdev.bot.zara.util.ProductLinks.extractProductId;

/**
 * Operator/testing API: trigger checks by hand, inspect subscriptions and metrics
 * without waiting for timers or opening Telegram. Swagger UI: /swagger-ui.html.
 * No authentication — local runs only, never expose publicly.
 *
 * @author i.bogatskii
 */
@Tag(name = "Operator API", description = "Тестирование и диагностика бота (без аутентификации — только локально)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OperatorController {

    private final PageService pageService;
    private final ProductCardCache productCardCache;
    private final SubscriptionService subscriptionService;
    private final MonitoringScheduler monitoringScheduler;
    private final SelectorCanary selectorCanary;

    public record SubscribeRequest(long chatId, String link, Set<String> sizes) {
    }

    public record UnsubscribeRequest(long chatId, String productKey, Set<String> sizes) {
    }

    @Operation(summary = "Живая проверка наличия размеров и цены по ссылке (JSON API → Selenium-фоллбек)")
    @GetMapping("/availability")
    public ProductSnapshot availability(@RequestParam final String link) {
        return this.pageService.checkProductSizesAvailability(link);
    }

    @Operation(summary = "Карточка товара (кэш → JSON API → Selenium); fresh=true — мимо кэша")
    @PostMapping("/card")
    public ProductCard card(
            @RequestParam final String link,
            @RequestParam(defaultValue = "false") final boolean fresh
    ) {
        final var productKey = extractProductId(link);
        if (fresh) {
            this.productCardCache.invalidate(productKey);
        }
        return this.productCardCache.getOrLoad(productKey, link);
    }

    @Operation(summary = "Активные подписки чата: productKey → размеры")
    @GetMapping("/subscriptions/{chatId}")
    public Map<String, Set<String>> subscriptions(@PathVariable final long chatId) {
        return this.subscriptionService.getAllSubscribedSizes(chatId);
    }

    @Operation(summary = "Все отслеживаемые товары и их подписчики")
    @GetMapping("/subscriptions")
    public Map<String, Map<Long, Set<String>>> allSubscriptions() {
        final var result = new HashMap<String, Map<Long, Set<String>>>();
        for (final var productKey : this.subscriptionService.activeProductKeys()) {
            result.put(productKey, this.subscriptionService.getSubscribersByProduct(productKey));
        }
        return result;
    }

    @Operation(summary = "Подписать чат на размеры товара (карточка загрузится по ссылке)")
    @PostMapping("/subscriptions")
    public Map<String, Set<String>> subscribe(@RequestBody final SubscribeRequest request) {
        final var productKey = extractProductId(request.link());
        final var card = this.productCardCache.getOrLoad(productKey, request.link());

        this.subscriptionService.subscribe(request.chatId(), card, normalize(request.sizes()));
        return this.subscriptionService.getAllSubscribedSizes(request.chatId());
    }

    @Operation(summary = "Отписать чат от размеров; пустой sizes — от товара целиком")
    @DeleteMapping("/subscriptions")
    public Map<String, Set<String>> unsubscribe(@RequestBody final UnsubscribeRequest request) {
        if (request.sizes() == null || request.sizes().isEmpty()) {
            this.subscriptionService.unsubscribeAll(request.chatId(), request.productKey(), USER_ACTION);
        } else {
            this.subscriptionService.unsubscribe(
                    request.chatId(), request.productKey(), normalize(request.sizes()), USER_ACTION);
        }
        return this.subscriptionService.getAllSubscribedSizes(request.chatId());
    }

    @Operation(summary = "Запустить тик мониторинга прямо сейчас (не дожидаясь таймера)")
    @PostMapping("/monitor/run")
    public String runMonitor() {
        this.monitoringScheduler.monitor();
        return "ok";
    }

    @Operation(summary = "Запустить канарейку селекторов прямо сейчас")
    @PostMapping("/canary/run")
    public String runCanary() {
        this.selectorCanary.checkSelectors();
        return "ok";
    }

    @Operation(summary = "Метрики кэша карточек (Caffeine)")
    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        return this.productCardCache.statsSnapshot();
    }

    private Set<String> normalize(final Set<String> sizes) {
        if (sizes == null) {
            return Set.of();
        }

        return sizes.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.replaceAll("\\s+", "").toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
