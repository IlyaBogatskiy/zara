package com.ibdev.bot.zara.notify;

import com.ibdev.bot.zara.notify.NotifyEvent.PriceMoved;
import com.ibdev.bot.zara.notify.NotifyEvent.SizeAppeared;
import com.ibdev.bot.zara.notify.NotifyEvent.SizeSoldOut;
import com.ibdev.bot.zara.notify.NotifyEvent.WholeAvailable;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tallies user-facing monitoring events since the last drain, so the daily activity summary can
 * report "how the bot lived" (how many restocks / sell-outs / price moves went out) rather than only
 * "what broke". Fed by {@link UserNotifier} as it sends reports; drained (and reset) once a day.
 *
 * @author i.bogatskii
 */
@Component
public class ActivityStats {

    private final AtomicLong appeared = new AtomicLong();
    private final AtomicLong soldOut = new AtomicLong();
    private final AtomicLong priceMoved = new AtomicLong();
    private final AtomicLong wholeAvailable = new AtomicLong();

    /**
     * A point-in-time snapshot of the counters.
     */
    public record Counts(long appeared, long soldOut, long priceMoved, long wholeAvailable) {
        public long total() {
            return this.appeared + this.soldOut + this.priceMoved + this.wholeAvailable;
        }
    }

    public void record(final NotifyEvent event) {
        switch (event) {
            case SizeAppeared ignored -> this.appeared.incrementAndGet();
            case SizeSoldOut ignored -> this.soldOut.incrementAndGet();
            case PriceMoved ignored -> this.priceMoved.incrementAndGet();
            case WholeAvailable ignored -> this.wholeAvailable.incrementAndGet();
        }
    }

    /**
     * Returns the current counts and resets them to zero.
     */
    public Counts drain() {
        return new Counts(
                this.appeared.getAndSet(0),
                this.soldOut.getAndSet(0),
                this.priceMoved.getAndSet(0),
                this.wholeAvailable.getAndSet(0));
    }
}
