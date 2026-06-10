package com.ibdev.bot.zara.telegram;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-chat dialog state. Caffeine with expireAfterAccess: abandoned sessions
 * expire on their own (the map used to never be cleaned).
 *
 * @author i.bogatskii
 */
@Component
public class SessionCache {

    private final Cache<Long, ChatSession> sessions = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    public ChatSession getOrCreate(final long chatId) {
        return this.sessions.get(chatId, k -> new ChatSession());
    }
}
