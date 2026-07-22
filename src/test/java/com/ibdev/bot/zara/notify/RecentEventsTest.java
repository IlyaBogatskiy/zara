package com.ibdev.bot.zara.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author i.bogatskii
 */
class RecentEventsTest {

    private final RecentEvents events = new RecentEvents();

    @Test
    void newestFirstAndLimited() {
        events.record(1L, "a");
        events.record(2L, "b");
        events.record(3L, "c");

        assertThat(events.recent(2)).extracting(RecentEvents.Event::text).containsExactly("c", "b");
        assertThat(events.recent(10)).extracting(RecentEvents.Event::text).containsExactly("c", "b", "a");
    }

    @Test
    void ringDropsOldestPastCapacity() {
        for (int i = 0; i < 60; i++) {
            events.record(i, "e" + i);
        }
        final var recent = events.recent(100);

        assertThat(recent).hasSize(50);
        assertThat(recent.get(0).text()).isEqualTo("e59");
        assertThat(recent.get(recent.size() - 1).text()).isEqualTo("e10");
    }

    @Test
    void blankIgnored() {
        events.record(1L, "  ");
        events.record(2L, null);
        assertThat(events.recent(10)).isEmpty();
    }
}
