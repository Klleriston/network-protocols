package dev.klleriston.netlab.metrics;

import java.util.Arrays;

/** Estatísticas de RTT (mín, média, p95 por nearest-rank, máx) a partir de amostras em nanos. */
public final class RttStats {

    private long[] samples = new long[256];
    private int size;
    private long sum;

    public void add(long rttNanos) {
        if (size == samples.length) samples = Arrays.copyOf(samples, size * 2);
        samples[size++] = rttNanos;
        sum += rttNanos;
    }

    public long minMicros() {
        if (size == 0) return 0;
        long m = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) m = Math.min(m, samples[i]);
        return m / 1_000;
    }

    public long maxMicros() {
        if (size == 0) return 0;
        long m = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) m = Math.max(m, samples[i]);
        return m / 1_000;
    }

    public long avgMicros() {
        return size == 0 ? 0 : sum / size / 1_000;
    }

    public long p95Micros() {
        if (size == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(0.95 * size) - 1;
        return sorted[idx] / 1_000;
    }
}
