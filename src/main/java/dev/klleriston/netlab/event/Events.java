package dev.klleriston.netlab.event;

/** Todos os eventos do laboratório. */
public final class Events {

    private Events() { }

    private static Json.Builder head(Event e) {
        return Json.object()
                .field("type", e.type())
                .field("timestampNanos", e.timestampNanos())
                .field("protocol", e.protocol().name());
    }

    public record ExperimentStarted(long timestampNanos, String name) implements Event {
        public Protocol protocol() { return Protocol.NONE; }
        public String toJson() { return head(this).field("name", name).build(); }
    }

    public record ExperimentFinished(long timestampNanos, String name, String summary) implements Event {
        public Protocol protocol() { return Protocol.NONE; }
        public String toJson() { return head(this).field("name", name).field("summary", summary).build(); }
    }

    public record ExperimentAborted(long timestampNanos, String name) implements Event {
        public Protocol protocol() { return Protocol.NONE; }
        public String toJson() { return head(this).field("name", name).build(); }
    }

    public record ExperimentFailed(long timestampNanos, String name, String reason) implements Event {
        public Protocol protocol() { return Protocol.NONE; }
        public String toJson() { return head(this).field("name", name).field("reason", reason).build(); }
    }

    public record ConnectionEstablished(long timestampNanos, Protocol protocol, String remote,
                                        long handshakeMicros) implements Event {
        public String toJson() {
            return head(this).field("remote", remote).field("handshakeMicros", handshakeMicros).build();
        }
    }

    public record ConnectionClosed(long timestampNanos, Protocol protocol, String remote,
                                   boolean graceful) implements Event {
        public String toJson() { return head(this).field("remote", remote).field("graceful", graceful).build(); }
    }

    public record ConnectionReset(long timestampNanos, Protocol protocol, String remote) implements Event {
        public String toJson() { return head(this).field("remote", remote).build(); }
    }

    public record ConnectionRefused(long timestampNanos, Protocol protocol, String remote) implements Event {
        public String toJson() { return head(this).field("remote", remote).build(); }
    }

    public record ConnectTimeout(long timestampNanos, Protocol protocol, String remote) implements Event {
        public String toJson() { return head(this).field("remote", remote).build(); }
    }

    public record UdpPortUnreachable(long timestampNanos, String remote) implements Event {
        public Protocol protocol() { return Protocol.UDP; }
        public String toJson() { return head(this).field("remote", remote).build(); }
    }

    public record PacketSent(long timestampNanos, Protocol protocol, long seq, int bytes) implements Event {
        public String toJson() { return head(this).field("seq", seq).field("bytes", bytes).build(); }
    }

    public record PacketEchoed(long timestampNanos, Protocol protocol, long seq, long rttNanos,
                               int bytes) implements Event {
        public String toJson() {
            return head(this).field("seq", seq).field("rttNanos", rttNanos).field("bytes", bytes).build();
        }
    }

    public record PacketLost(long timestampNanos, Protocol protocol, long seq) implements Event {
        public String toJson() { return head(this).field("seq", seq).build(); }
    }

    public record PacketReordered(long timestampNanos, Protocol protocol, long seq,
                                  long highestSeenSeq) implements Event {
        public String toJson() { return head(this).field("seq", seq).field("highestSeenSeq", highestSeenSeq).build(); }
    }

    public record PacketLate(long timestampNanos, Protocol protocol, long seq) implements Event {
        public String toJson() { return head(this).field("seq", seq).build(); }
    }

    public record MetricsSnapshot(long timestampNanos, Protocol protocol, long sent, long echoed, long lost,
                                  long reordered, long late, double lossPercent, long rttMinMicros,
                                  long rttAvgMicros, long rttP95Micros, long rttMaxMicros, long jitterMicros,
                                  long throughputBytesPerSec, long droppedEvents) implements Event {
        public String toJson() {
            return head(this)
                    .field("sent", sent).field("echoed", echoed).field("lost", lost)
                    .field("reordered", reordered).field("late", late)
                    .field("lossPercent", lossPercent)
                    .field("rttMinMicros", rttMinMicros).field("rttAvgMicros", rttAvgMicros)
                    .field("rttP95Micros", rttP95Micros).field("rttMaxMicros", rttMaxMicros)
                    .field("jitterMicros", jitterMicros)
                    .field("throughputBytesPerSec", throughputBytesPerSec)
                    .field("droppedEvents", droppedEvents)
                    .build();
        }
    }

    public record ImpairmentChanged(long timestampNanos, int lossPercent, int delayMs, int jitterMs,
                                    int reorderPercent) implements Event {
        public Protocol protocol() { return Protocol.NONE; }
        public String toJson() {
            return head(this).field("lossPercent", lossPercent).field("delayMs", delayMs)
                    .field("jitterMs", jitterMs).field("reorderPercent", reorderPercent).build();
        }
    }

    public record Suppressed(long timestampNanos, long count) implements Event {
        public Protocol protocol() { return Protocol.NONE; }
        public String toJson() { return head(this).field("count", count).build(); }
    }
}
