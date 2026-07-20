package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
class AdminNotifierTest {

    @Mock
    private TelegramBot telegramBot;

    private AdminNotifier notifier(final Long adminChatId) {
        final var props = new ZaraProperties();
        props.setAdminChatId(adminChatId);
        return new AdminNotifier(telegramBot, props);
    }

    @Test
    void noticeIsSentToAdminChatEveryTimeWithoutThrottle() {
        final var notifier = notifier(777L);

        notifier.notice("first");
        notifier.notice("second");

        final var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(m -> (String) m.getParameters().get("text"))
                .containsExactly("first", "second");
    }

    @Test
    void noticeIsNotSentWhenNoAdminChatConfigured() {
        final var notifier = notifier(0L);

        notifier.notice("nobody home");

        verify(telegramBot, never()).execute(any());
    }
}
