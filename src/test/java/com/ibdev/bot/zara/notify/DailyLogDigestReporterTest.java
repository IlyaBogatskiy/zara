package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.LogDigestCollector.Entry;
import com.ibdev.bot.zara.notify.LogDigestCollector.Snapshot;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author i.bogatskii
 */
@ExtendWith(MockitoExtension.class)
class DailyLogDigestReporterTest {

    @Mock
    private LogDigestCollector collector;

    @Mock
    private TelegramBot telegramBot;

    private ZaraProperties properties;
    private DailyLogDigestReporter reporter;

    @BeforeEach
    void setUp() {
        properties = new ZaraProperties();
        properties.getLogDigest().setEnabled(true);
        properties.setAdminChatId(555L);
        reporter = new DailyLogDigestReporter(collector, telegramBot, properties);
    }

    private static Entry entry(final String level, final String message) {
        return new Entry(0L, level, "com.ibdev.bot.zara.Test", "scheduling-1", message, null);
    }

    private SendDocument captureSentDocument() {
        final var captor = ArgumentCaptor.forClass(SendDocument.class);
        verify(telegramBot).execute(captor.capture());
        return captor.getValue();
    }

    @Test
    void rendersCountsMessagesAndFileName() {
        final var snapshot = new Snapshot(List.of(
                entry("ERROR", "canary mismatch on p06318052"),
                entry("WARN", "Zara API check failed, falling back to Selenium")), 0);

        final var rendered = reporter.render(snapshot, Instant.parse("2026-07-19T20:55:00Z"), ZoneId.of("UTC"));

        assertThat(rendered.fileName()).isEqualTo("zara-warn-error-2026-07-19.txt");
        assertThat(rendered.caption()).contains("ERROR 1", "WARN 1");
        assertThat(rendered.body())
                .contains("canary mismatch on p06318052", "Zara API check failed", "ERROR", "WARN");
    }

    @Test
    void reportsDroppedCountWhenBufferOverflowed() {
        final var snapshot = new Snapshot(List.of(entry("WARN", "w")), 12);

        final var rendered = reporter.render(snapshot, Instant.parse("2026-07-19T20:55:00Z"), ZoneId.of("UTC"));

        assertThat(rendered.body()).contains("+12");
    }

    @Test
    void flushSendsDocumentToAdminChatWhenThereAreEntries() {
        when(collector.drain()).thenReturn(new Snapshot(List.of(entry("ERROR", "boom")), 0));

        reporter.flush();

        final var document = captureSentDocument();
        assertThat(document.getCaption()).contains("ERROR 1");
    }

    @Test
    void flushSendsNothingOnACleanDay() {
        when(collector.drain()).thenReturn(new Snapshot(List.of(), 0));

        reporter.flush();

        verify(telegramBot, never()).execute(any());
    }

    @Test
    void flushDoesNotDrainOrSendWhenDisabled() {
        properties.getLogDigest().setEnabled(false);

        reporter.flush();

        verify(collector, never()).drain();
        verify(telegramBot, never()).execute(any());
    }

    @Test
    void flushLogsButDoesNotSendWhenNoAdminChatConfigured() {
        properties.setAdminChatId(0L);
        when(collector.drain()).thenReturn(new Snapshot(List.of(entry("ERROR", "boom")), 0));

        reporter.flush();

        verify(telegramBot, never()).execute(any());
    }
}
