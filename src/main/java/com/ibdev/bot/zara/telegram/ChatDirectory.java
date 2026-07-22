package com.ibdev.bot.zara.telegram;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory chat → identity map, populated from incoming updates so the admin views can show who a
 * chat is (@username / name) instead of a bare numeric id. Deliberately not persisted: adding a table
 * would break {@code ddl-auto: validate} in prod, and the id — always known from subscriptions — is a
 * stable fallback. After a restart the name fills back in as soon as the user next interacts.
 *
 * @author i.bogatskii
 */
@Component
public class ChatDirectory {

    public record ChatInfo(String username, String firstName, String lastName) {
    }

    private final Map<Long, ChatInfo> byChat = new ConcurrentHashMap<>();

    /**
     * Records (overwrites) what we know about a chat. A fully-empty identity is ignored so a later
     * real one is not lost.
     */
    public void record(final long chatId, final String username, final String firstName, final String lastName) {
        if (isBlank(username) && isBlank(firstName) && isBlank(lastName)) {
            return;
        }
        this.byChat.put(chatId, new ChatInfo(username, firstName, lastName));
    }

    public ChatInfo get(final long chatId) {
        return this.byChat.get(chatId);
    }

    /**
     * Human label for the admin views: "чат &lt;id&gt;" plus "@username" when known, otherwise the
     * name, otherwise just the id.
     */
    public String label(final long chatId) {
        final var base = "чат " + chatId;
        final var info = this.byChat.get(chatId);
        if (info == null) {
            return base;
        }
        if (!isBlank(info.username())) {
            return base + " · @" + info.username();
        }
        final var name = fullName(info);
        return name.isBlank() ? base : base + " · " + name;
    }

    private String fullName(final ChatInfo info) {
        return (orEmpty(info.firstName()) + " " + orEmpty(info.lastName())).trim();
    }

    private String orEmpty(final String s) {
        return s == null ? "" : s;
    }

    private boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }
}
