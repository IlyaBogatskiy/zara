package com.ibdev.bot.zara.notify;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.response.BaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
class UserNotifierTest {

    @Mock
    private TelegramBot telegramBot;

    @Mock
    private AdminNotifier adminNotifier;

    private UserNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new UserNotifier(telegramBot, adminNotifier);
    }

    private List<NotifyEvent> oneAppeared() {
        return List.of(new NotifyEvent.SizeAppeared("K1", "Куртка", "https://z/k1", "S", false));
    }

    @Test
    void successfulDeliveryDoesNotAlertAdmin() {
        final var ok = mock(BaseResponse.class);
        when(ok.isOk()).thenReturn(true);
        when(telegramBot.execute(any())).thenReturn(ok);

        notifier.sendReport(1L, oneAppeared());

        verify(adminNotifier, never()).alert(any(), any());
    }

    @Test
    void failedDeliveryAlertsAdmin() {
        final var failed = mock(BaseResponse.class);
        when(failed.isOk()).thenReturn(false);
        when(failed.errorCode()).thenReturn(403);
        when(failed.description()).thenReturn("Forbidden: bot was blocked by the user");
        when(telegramBot.execute(any())).thenReturn(failed);

        notifier.sendReport(1L, oneAppeared());

        verify(adminNotifier).alert(eq("delivery-failed"), any());
    }

    @Test
    void thrownSendAlertsAdmin() {
        when(telegramBot.execute(any())).thenThrow(new RuntimeException("network down"));

        notifier.sendReport(1L, oneAppeared());

        verify(adminNotifier).alert(eq("delivery-failed"), any());
    }
}
