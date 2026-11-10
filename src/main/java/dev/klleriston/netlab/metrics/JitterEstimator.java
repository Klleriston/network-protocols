package dev.klleriston.netlab.metrics;

/** Jitter conforme RFC 3550: J += (|D| - J) / 16, com D a diferença entre RTTs consecutivos. */
public final class JitterEstimator {

    private double jitterNanos;
    private long previous;
    private boolean hasPrevious;

    public void add(long rttNanos) {
        if (hasPrevious) {
            double d = Math.abs(rttNanos - previous);
            jitterNanos += (d - jitterNanos) / 16.0;
        }
        previous = rttNanos;
        hasPrevious = true;
    }

    public long jitterMicros() {
        return (long) (jitterNanos / 1_000.0);
    }
}
