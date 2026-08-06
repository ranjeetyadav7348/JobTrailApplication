package com.jobtrail.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, live view of what the dispatcher is doing. Kept in its own bean so
 * both the dispatcher and the queue endpoint can reach it without a cycle.
 */
@Component
public class DispatcherStatus {

    /** Epoch millis before which no further send may start. */
    private final AtomicLong nextAllowedSendAtMillis = new AtomicLong(0L);

    private volatile String state = "IDLE";
    private volatile String detail = "Nothing in the queue";

    public void set(String state, String detail) {
        this.state = state;
        this.detail = detail;
    }

    public String getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    /** Blocks the next send until {@code gapMillis} from now. */
    public void holdFor(long gapMillis) {
        nextAllowedSendAtMillis.set(System.currentTimeMillis() + gapMillis);
    }

    public long nextAllowedSendAtMillis() {
        return nextAllowedSendAtMillis.get();
    }

    public boolean slotOpen() {
        return System.currentTimeMillis() >= nextAllowedSendAtMillis.get();
    }

    /** Whole seconds until the pacing gap expires; 0 when a send may start now. */
    public long secondsUntilSlot() {
        long remaining = nextAllowedSendAtMillis.get() - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }
}
