package dev.klleriston.netlab.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JitterEstimatorTest {

    @Test
    void constantRttHasZeroJitter() {
        JitterEstimator j = new JitterEstimator();
        for (int i = 0; i < 10; i++) j.add(5_000_000L);
        assertEquals(0, j.jitterMicros());
    }

    @Test
    void followsRfc3550Recurrence() {
        JitterEstimator j = new JitterEstimator();
        j.add(10_000_000L);
        j.add(26_000_000L);
        assertEquals(1_000, j.jitterMicros());
        j.add(10_000_000L);
        assertEquals(1_937, j.jitterMicros());
    }

    @Test
    void singleSampleHasZeroJitter() {
        JitterEstimator j = new JitterEstimator();
        j.add(10_000_000L);
        assertEquals(0, j.jitterMicros());
    }
}
