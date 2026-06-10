package com.ibdev.bot.zara.telegram;

import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated pool for user-requested card loads: the Telegram updates thread never
 * blocks on scraping (the Selenium fallback takes up to 30 s).
 * Pool size 2 is a deliberate cap on concurrent headless Chrome instances.
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
public class ScrapingExecutor {

    private final ExecutorService executor = Executors.newFixedThreadPool(2, namedThreadFactory());

    public void execute(final Runnable task) {
        this.executor.execute(() -> {
            try {
                task.run();
            } catch (final Exception e) {
                log.error("Scraping task failed: {}", e.getMessage(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        this.executor.shutdownNow();
    }

    private static ThreadFactory namedThreadFactory() {
        final var counter = new AtomicInteger();
        return runnable -> {
            final var thread = new Thread(runnable, "scraping-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
