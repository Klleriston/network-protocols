package dev.klleriston.netlab.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SseRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000);

    @Test
    void allowsFiftyPerSecondThenSuppresses() {
        SseRateLimiter limiter = new SseRateLimiter(50, clock::get);
        int allowed = 0;
        for (int i = 0; i < 60; i++) {
            if (limiter.tryAcquire()) allowed++;
            clock.addAndGet(1_000_000); // 60 ms no total: mesma janela
        }
        assertEquals(50, allowed);
        assertEquals(0, limiter.takeSuppressed());
    }

    @Test
    void reportsSuppressedCountAtNextWindow() {
        SseRateLimiter limiter = new SseRateLimiter(50, clock::get);
        for (int i = 0; i < 60; i++) limiter.tryAcquire();
        clock.addAndGet(1_000_000_000L);
        assertEquals(10, limiter.takeSuppressed());
        assertEquals(0, limiter.takeSuppressed());
        for (int i = 0; i < 50; i++) assertEquals(true, limiter.tryAcquire());
    }
}
