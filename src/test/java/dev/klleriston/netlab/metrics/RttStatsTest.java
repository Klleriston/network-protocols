package dev.klleriston.netlab.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RttStatsTest {

    @Test
    void statsForKnownSamples() {
        RttStats s = new RttStats();
        for (int i = 1; i <= 100; i++) s.add(i * 1_000_000L);
        assertEquals(1_000, s.minMicros());
        assertEquals(100_000, s.maxMicros());
        assertEquals(50_500, s.avgMicros());
        assertEquals(95_000, s.p95Micros());
    }

    @Test
    void emptyStatsAreZero() {
        RttStats s = new RttStats();
        assertEquals(0, s.minMicros());
        assertEquals(0, s.avgMicros());
        assertEquals(0, s.p95Micros());
        assertEquals(0, s.maxMicros());
    }
}
