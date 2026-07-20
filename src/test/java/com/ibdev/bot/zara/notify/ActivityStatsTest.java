package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.client.PriceInfo;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author i.bogatskii
 */
class ActivityStatsTest {

    private final ActivityStats stats = new ActivityStats();

    @Test
    void classifiesEachEventTypeAndDrainResets() {
        stats.record(new NotifyEvent.SizeAppeared("k", "n", "l", "S", false));
        stats.record(new NotifyEvent.SizeAppeared("k", "n", "l", "M", false));
        stats.record(new NotifyEvent.SizeSoldOut("k", "n", "l", "L"));
        stats.record(new NotifyEvent.PriceMoved("k", "n", "l", new PriceInfo(200, "EUR", 2), new PriceInfo(100, "EUR", 2)));
        stats.record(new NotifyEvent.WholeAvailable("k", "n", "l", Set.of("S"), Set.of("XL")));

        final var counts = stats.drain();
        assertThat(counts.appeared()).isEqualTo(2);
        assertThat(counts.soldOut()).isEqualTo(1);
        assertThat(counts.priceMoved()).isEqualTo(1);
        assertThat(counts.wholeAvailable()).isEqualTo(1);
        assertThat(counts.total()).isEqualTo(5);

        assertThat(stats.drain().total()).isZero();
    }
}
