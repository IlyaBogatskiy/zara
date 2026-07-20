package com.ibdev.bot.zara.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.ibdev.bot.zara.config.ZaraProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;

/**
 * A bounded in-memory Logback appender that captures every log event at level WARN or above (across
 * all loggers) so a scheduled job can flush them once a day. The SQL DEBUG/TRACE flood never enters
 * the buffer — the level check drops it — so this stays tiny regardless of overall log volume, and
 * carries none of the bound-parameter data values those TRACE lines leak.
 * <p>
 * Attaches itself to the root logger on startup (mirroring the {@code ListAppender} pattern the
 * scheduler tests use) rather than via {@code logback-spring.xml}, so the whole feature is contained
 * in one Spring bean. The buffer is a fixed-capacity ring: once full it drops the oldest event and
 * counts the drop, which the digest surfaces at the top. Draining returns a snapshot and resets.
 *
 * @author i.bogatskii
 */
@Component
public class LogDigestCollector extends AppenderBase<ILoggingEvent> {

    private final int maxEntries;
    private final ArrayDeque<Entry> buffer;
    private int dropped;

    /**
     * One captured WARN/ERROR event, copied out of the (reusable) {@link ILoggingEvent} at capture
     * time so nothing downstream depends on Logback's event lifecycle. {@code throwable} is the
     * pre-rendered stack trace, or null when the event carried none.
     */
    public record Entry(
            long timestampMillis, String level, String logger, String thread, String message, String throwable) {
    }

    /**
     * Immutable snapshot handed to the digest job: the captured events (oldest first) and how many
     * older events were dropped because the ring was full.
     */
    public record Snapshot(List<Entry> entries, int dropped) {
    }

    public LogDigestCollector(final ZaraProperties properties) {
        this.maxEntries = Math.max(1, properties.getLogDigest().getMaxEntries());
        this.buffer = new ArrayDeque<>(Math.min(this.maxEntries, 1024));
    }

    @PostConstruct
    public void attach() {
        final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        setName("log-digest-collector");
        start();
        root.addAppender(this);
    }

    @PreDestroy
    public void detach() {
        final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(this);
        stop();
    }

    @Override
    protected void append(final ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }
        final var throwableProxy = event.getThrowableProxy();
        final var entry = new Entry(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                throwableProxy == null ? null : ThrowableProxyUtil.asString(throwableProxy));
        synchronized (this.buffer) {
            if (this.buffer.size() >= this.maxEntries) {
                this.buffer.pollFirst();
                this.dropped++;
            }
            this.buffer.addLast(entry);
        }
    }

    /**
     * Returns and clears everything captured since the previous drain, plus the count of events that
     * were dropped because the ring filled up.
     */
    public Snapshot drain() {
        synchronized (this.buffer) {
            final var entries = List.copyOf(this.buffer);
            final var droppedNow = this.dropped;
            this.buffer.clear();
            this.dropped = 0;
            return new Snapshot(entries, droppedNow);
        }
    }
}
