package com.ibdev.bot.zara.notify;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A small in-memory ring buffer of the most recent admin-visible events — user notifications the bot
 * sent (appeared / sold out / price / whole) and operator alerts — so the admin can glance at "what
 * happened recently" from the menu without waiting for the daily digest. In-memory only (a restart
 * clears it); the daily digest / activity summary remain the durable records.
 *
 * @author i.bogatskii
 */
@Component
public class RecentEvents {

    public record Event(long at, String text) {
    }

    private static final int CAPACITY = 50;

    private final Deque<Event> buffer = new ArrayDeque<>();

    public void record(final String text) {
        record(System.currentTimeMillis(), text);
    }

    void record(final long at, final String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        final var event = new Event(at, text.strip());
        synchronized (this.buffer) {
            this.buffer.addLast(event);
            while (this.buffer.size() > CAPACITY) {
                this.buffer.pollFirst();
            }
        }
    }

    /**
     * Up to {@code limit} most recent events, newest first.
     */
    public List<Event> recent(final int limit) {
        final List<Event> all;
        synchronized (this.buffer) {
            all = new ArrayList<>(this.buffer);
        }
        Collections.reverse(all);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }
}
