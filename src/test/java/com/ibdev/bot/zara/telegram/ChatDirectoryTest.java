package com.ibdev.bot.zara.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author i.bogatskii
 */
class ChatDirectoryTest {

    private final ChatDirectory directory = new ChatDirectory();

    @Test
    void unknownChatLabelsWithBareId() {
        assertThat(directory.label(42L)).isEqualTo("чат 42");
    }

    @Test
    void usernameWins() {
        directory.record(42L, "alice", "Alice", "Wonder");
        assertThat(directory.label(42L)).isEqualTo("чат 42 · @alice");
    }

    @Test
    void fallsBackToNameWhenNoUsername() {
        directory.record(42L, null, "Alice", "Wonder");
        assertThat(directory.label(42L)).isEqualTo("чат 42 · Alice Wonder");
    }

    @Test
    void firstNameOnlyIsFine() {
        directory.record(42L, "  ", "Alice", null);
        assertThat(directory.label(42L)).isEqualTo("чат 42 · Alice");
    }

    @Test
    void fullyBlankIdentityIsIgnoredSoIdRemains() {
        directory.record(42L, null, null, null);
        directory.record(42L, "", "  ", "");
        assertThat(directory.label(42L)).isEqualTo("чат 42");
        assertThat(directory.get(42L)).isNull();
    }
}
