package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.config.ZaraProperties;
import com.ibdev.bot.zara.notify.LogDigestCollector.Entry;
import com.ibdev.bot.zara.notify.LogDigestCollector.Snapshot;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Flushes the day's collected WARN/ERROR events ({@link LogDigestCollector}) to the admin Telegram
 * chat as a text file, once a day (cron). Nothing is sent on a clean day — the digest exists to
 * surface problems that no dedicated alert reported, so silence means "nothing to report". When no
 * admin chat is configured the digest is logged as produced-but-undeliverable instead of lost.
 *
 * @author i.bogatskii
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class DailyLogDigestReporter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LogDigestCollector collector;
    private final TelegramBot telegramBot;
    private final ZaraProperties properties;

    /**
     * The rendered digest ready to ship: a Telegram-safe short {@code caption}, the attached file's
     * {@code fileName}, and its full text {@code body}.
     */
    public record Rendered(String fileName, String caption, String body) {
    }

    @Scheduled(cron = "${zara.log-digest.cron:0 55 23 * * *}", zone = "${zara.log-digest.zone:}")
    public void flush() {
        if (!this.properties.getLogDigest().isEnabled()) {
            return;
        }
        final var snapshot = this.collector.drain();
        if (snapshot.entries().isEmpty()) {
            log.info("Log digest: no WARN/ERROR since the last flush — nothing to send.");
            return;
        }

        final var rendered = render(snapshot, Instant.now(), zoneId());

        final var adminChatId = this.properties.getAdminChatId();
        if (adminChatId == null || adminChatId == 0) {
            log.info("Log digest produced ({} entries) but no zara.admin-chat-id is set — not delivered.\n{}",
                    snapshot.entries().size(), rendered.body());
            return;
        }

        try {
            this.telegramBot.execute(new SendDocument(adminChatId, rendered.body().getBytes(StandardCharsets.UTF_8))
                    .fileName(rendered.fileName())
                    .contentType("text/plain")
                    .caption(rendered.caption()));
        } catch (final Exception e) {
            log.error("Failed to deliver the daily log digest: {}", e.getMessage());
        }
    }

    /**
     * Builds the digest text, its file name and a short caption from a snapshot. Pure (no I/O, no
     * clock of its own — {@code now}/{@code zone} are passed in) so it is directly unit-testable.
     */
    public Rendered render(final Snapshot snapshot, final Instant now, final ZoneId zone) {
        var errors = 0;
        var warns = 0;
        for (final var entry : snapshot.entries()) {
            if ("ERROR".equals(entry.level())) {
                errors++;
            } else {
                warns++;
            }
        }

        final var at = now.atZone(zone);
        final var day = DAY.format(at);
        final var total = snapshot.entries().size();

        final var caption = String.format(
                "🗒 Zara: за сутки %d проблемных записей (ERROR %d, WARN %d). Подробности в файле.",
                total, errors, warns);

        final var body = new StringBuilder();
        body.append("Zara bot — дайджест WARN/ERROR\n");
        body.append("Сформирован: ").append(TIMESTAMP.format(at)).append(" (").append(zone).append(")\n");
        body.append("Всего: ").append(total).append(" (ERROR ").append(errors).append(", WARN ").append(warns).append(")");
        if (snapshot.dropped() > 0) {
            body.append("  [+").append(snapshot.dropped()).append(" отброшено: буфер переполнен]");
        }
        body.append('\n');

        for (final var entry : snapshot.entries()) {
            body.append("\n────────────────────────────────────────\n");
            body.append(TIMESTAMP.format(Instant.ofEpochMilli(entry.timestampMillis()).atZone(zone)))
                    .append("  ").append(entry.level())
                    .append("  ").append(entry.logger())
                    .append("  [").append(entry.thread()).append("]\n");
            body.append("  ").append(entry.message()).append('\n');
            if (entry.throwable() != null) {
                body.append(entry.throwable()).append('\n');
            }
        }

        return new Rendered("zara-warn-error-" + day + ".txt", caption, body.toString());
    }

    private ZoneId zoneId() {
        final var configured = this.properties.getLogDigest().getZone();
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(configured);
    }
}
