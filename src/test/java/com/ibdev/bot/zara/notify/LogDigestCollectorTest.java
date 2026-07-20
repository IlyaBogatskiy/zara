package com.ibdev.bot.zara.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.ibdev.bot.zara.config.ZaraProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author i.bogatskii
 */
class LogDigestCollectorTest {

    private Logger logger;
    private LogDigestCollector collector;

    private LogDigestCollector attach(final int maxEntries) {
        final var props = new ZaraProperties();
        props.getLogDigest().setMaxEntries(maxEntries);
        this.collector = new LogDigestCollector(props);
        this.logger = (Logger) LoggerFactory.getLogger("log-digest-collector-test");
        this.collector.setContext(this.logger.getLoggerContext());
        this.collector.start();
        this.logger.addAppender(this.collector);
        this.logger.setLevel(Level.TRACE);
        this.logger.setAdditive(false);
        return this.collector;
    }

    @AfterEach
    void detach() {
        if (this.logger != null && this.collector != null) {
            this.logger.detachAppender(this.collector);
            this.collector.stop();
        }
    }

    @Test
    void capturesOnlyWarnAndErrorNotLowerLevels() {
        attach(100);

        this.logger.trace("t");
        this.logger.debug("d");
        this.logger.info("i");
        this.logger.warn("a warning");
        this.logger.error("an error");

        final var snapshot = this.collector.drain();
        assertThat(snapshot.entries()).hasSize(2);
        assertThat(snapshot.entries()).extracting(LogDigestCollector.Entry::level)
                .containsExactly("WARN", "ERROR");
        assertThat(snapshot.entries()).extracting(LogDigestCollector.Entry::message)
                .containsExactly("a warning", "an error");
        assertThat(snapshot.dropped()).isZero();
    }

    @Test
    void keepsThrowableStackTrace() {
        attach(100);

        this.logger.error("boom", new IllegalStateException("kaboom"));

        final var entry = this.collector.drain().entries().getFirst();
        assertThat(entry.throwable()).contains("IllegalStateException", "kaboom");
    }

    @Test
    void ringBufferDropsOldestAndCountsDrops() {
        attach(3);

        for (int i = 1; i <= 5; i++) {
            this.logger.warn("w" + i);
        }

        final var snapshot = this.collector.drain();
        assertThat(snapshot.entries()).extracting(LogDigestCollector.Entry::message)
                .containsExactly("w3", "w4", "w5");
        assertThat(snapshot.dropped()).isEqualTo(2);
    }

    @Test
    void drainClearsTheBuffer() {
        attach(100);
        this.logger.warn("once");

        assertThat(this.collector.drain().entries()).hasSize(1);
        assertThat(this.collector.drain().entries()).isEmpty();
    }
}
