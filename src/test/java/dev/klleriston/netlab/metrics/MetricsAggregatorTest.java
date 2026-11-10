package dev.klleriston.netlab.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Events.MetricsSnapshot;
import dev.klleriston.netlab.event.Protocol;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MetricsAggregatorTest {

    private final AtomicLong clock = new AtomicLong();
    private final EventBus bus = new EventBus();
    private final MetricsAggregator agg = new MetricsAggregator(bus, clock::get);

    private MetricsSnapshot tcp() { return agg.snapshot().get(0); }
    private MetricsSnapshot udp() { return agg.snapshot().get(1); }

    @Test
    void countsPerProtocolSeparately() {
        agg.accept(new Events.PacketSent(0, Protocol.TCP, 1, 64));
        agg.accept(new Events.PacketSent(0, Protocol.UDP, 1, 64));
        agg.accept(new Events.PacketSent(0, Protocol.UDP, 2, 64));
        agg.accept(new Events.PacketEchoed(0, Protocol.UDP, 1, 2_000_000, 64));
        agg.accept(new Events.PacketReordered(0, Protocol.UDP, 2, 3));
        List<MetricsSnapshot> s = agg.snapshot();
        assertEquals(Protocol.TCP, s.get(0).protocol());
        assertEquals(Protocol.UDP, s.get(1).protocol());
        assertEquals(1, s.get(0).sent());
        assertEquals(0, s.get(0).echoed());
        assertEquals(2, s.get(1).sent());
        assertEquals(1, s.get(1).echoed());
        assertEquals(1, s.get(1).reordered());
        assertEquals(2_000, s.get(1).rttAvgMicros());
    }

    @Test
    void lossPercentIsLostOverSent() {
        for (int i = 0; i < 10; i++) agg.accept(new Events.PacketSent(0, Protocol.UDP, i, 64));
        agg.accept(new Events.PacketLost(0, Protocol.UDP, 3));
        assertEquals(10.0, udp().lossPercent(), 0.001);
    }

    @Test
    void lossPercentIsZeroWithoutSent() {
        assertEquals(0.0, tcp().lossPercent());
    }

    @Test
    void lateEchoCountsAsLateOnly() {
        agg.accept(new Events.PacketSent(0, Protocol.UDP, 1, 64));
        agg.accept(new Events.PacketLost(0, Protocol.UDP, 1));
        agg.accept(new Events.PacketLate(0, Protocol.UDP, 1));
        MetricsSnapshot u = udp();
        assertEquals(1, u.late());
        assertEquals(0, u.echoed());
        assertEquals(1, u.lost());
    }

    @Test
    void throughputIsEchoedBytesSinceLastSnapshotOverElapsed() {
        agg.snapshot();
        agg.accept(new Events.PacketEchoed(0, Protocol.TCP, 1, 1_000_000, 1000));
        clock.addAndGet(500_000_000L);
        assertEquals(2000, tcp().throughputBytesPerSec());
    }

    @Test
    void experimentStartedResetsCounters() {
        agg.accept(new Events.PacketSent(0, Protocol.TCP, 1, 64));
        agg.accept(new Events.PacketEchoed(0, Protocol.TCP, 1, 1_000_000, 64));
        agg.accept(new Events.ExperimentStarted(0, "x"));
        MetricsSnapshot t = tcp();
        assertEquals(0, t.sent());
        assertEquals(0, t.echoed());
        assertEquals(0, t.rttMaxMicros());
    }

    @Test
    void snapshotReportsDroppedEventsOfItsSubscription() {
        MetricsAggregator a = new MetricsAggregator(bus, clock::get, 1);
        assertEquals(0, a.snapshot().get(0).droppedEvents());
        a.start();
        try {
            for (int i = 0; i < 100_000; i++) bus.publish(new Events.PacketSent(0, Protocol.UDP, i, 64));
            assertTrue(a.snapshot().get(0).droppedEvents() > 0);
        } finally {
            a.close();
        }
    }
}
