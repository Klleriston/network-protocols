package dev.klleriston.netlab.dashboard;

import java.util.function.LongSupplier;

/** Limita eventos por janela de 1 s e conta os suprimidos para relatar na janela seguinte. */
public final class SseRateLimiter {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final int perSecond;
    private final LongSupplier nanoClock;
    private long windowStart;
    private int acquired;
    private long suppressedInWindow;
    private long pendingSuppressed;

    public SseRateLimiter(int perSecond, LongSupplier nanoClock) {
        if (perSecond < 1) throw new IllegalArgumentException("perSecond must be >= 1");
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
        this.windowStart = nanoClock.getAsLong();
    }

    private void roll() {
        long now = nanoClock.getAsLong();
        if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now;
            acquired = 0;
            pendingSuppressed += suppressedInWindow;
            suppressedInWindow = 0;
        }
    }

    public synchronized boolean tryAcquire() {
        roll();
        if (acquired < perSecond) {
            acquired++;
            return true;
        }
        suppressedInWindow++;
        return false;
    }

    /** Retorna e zera os suprimidos de janelas encerradas; 0 enquanto a janela atual não terminou. */
    public synchronized long takeSuppressed() {
        roll();
        long n = pendingSuppressed;
        pendingSuppressed = 0;
        return n;
    }
}
