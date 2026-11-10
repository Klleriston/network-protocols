package dev.klleriston.netlab.metrics;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Events.MetricsSnapshot;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.event.Subscription;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Consome eventos de pacote e publica {@link MetricsSnapshot} a cada 250 ms. */
public final class MetricsAggregator implements AutoCloseable {

    private static final int DEFAULT_CAPACITY = 100_000;
    private static final long PERIOD_MS = 250;

    private static final class State {
        long sent, echoed, lost, reordered, late, windowBytes;
        RttStats rtt = new RttStats();
        JitterEstimator jitter = new JitterEstimator();
    }

    private final EventBus bus;
    private final LongSupplier nanoClock;
    private final int subscriptionCapacity;
    private final State tcp = new State();
    private final State udp = new State();
    private long windowStart;
    private Subscription subscription;
    private ScheduledExecutorService scheduler;

    public MetricsAggregator(EventBus bus, LongSupplier nanoClock) {
        this(bus, nanoClock, DEFAULT_CAPACITY);
    }

    public MetricsAggregator(EventBus bus, LongSupplier nanoClock, int subscriptionCapacity) {
        this.bus = bus;
        this.nanoClock = nanoClock;
        this.subscriptionCapacity = subscriptionCapacity;
        this.windowStart = nanoClock.getAsLong();
    }

    private State of(Protocol p) {
        return switch (p) {
            case TCP -> tcp;
            case UDP -> udp;
            default -> null;
        };
    }

    public synchronized void accept(Event e) {
        if (e instanceof Events.ExperimentStarted) {
            reset(tcp);
            reset(udp);
            windowStart = nanoClock.getAsLong();
            return;
        }
        State s = of(e.protocol());
        if (s == null) return;
        switch (e) {
            case Events.PacketSent p -> s.sent++;
            case Events.PacketEchoed p -> {
                s.echoed++;
                s.windowBytes += p.bytes();
                s.rtt.add(p.rttNanos());
                s.jitter.add(p.rttNanos());
            }
            case Events.PacketLost p -> s.lost++;
            case Events.PacketReordered p -> s.reordered++;
            case Events.PacketLate p -> s.late++;
            default -> { }
        }
    }

    private static void reset(State s) {
        s.sent = s.echoed = s.lost = s.reordered = s.late = s.windowBytes = 0;
        s.rtt = new RttStats();
        s.jitter = new JitterEstimator();
    }

    /** Um snapshot por protocolo (TCP, UDP); reinicia a janela de vazão. */
    public synchronized List<MetricsSnapshot> snapshot() {
        long now = nanoClock.getAsLong();
        long elapsed = now - windowStart;
        long dropped = subscription == null ? 0 : subscription.droppedCount();
        List<MetricsSnapshot> out = List.of(build(now, Protocol.TCP, tcp, elapsed, dropped),
                build(now, Protocol.UDP, udp, elapsed, dropped));
        tcp.windowBytes = 0;
        udp.windowBytes = 0;
        windowStart = now;
        return out;
    }

    private static MetricsSnapshot build(long now, Protocol p, State s, long elapsed, long dropped) {
        double loss = s.sent == 0 ? 0.0 : s.lost * 100.0 / s.sent;
        long throughput = elapsed <= 0 ? 0 : (long) (s.windowBytes * 1e9 / elapsed);
        return new MetricsSnapshot(now, p, s.sent, s.echoed, s.lost, s.reordered, s.late, loss,
                s.rtt.minMicros(), s.rtt.avgMicros(), s.rtt.p95Micros(), s.rtt.maxMicros(),
                s.jitter.jitterMicros(), throughput, dropped);
    }

    public synchronized void start() {
        if (subscription != null) return;
        subscription = bus.subscribe("metrics", subscriptionCapacity, this::accept);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publish, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void publish() {
        try {
            for (MetricsSnapshot s : snapshot()) bus.publish(s);
        } catch (RuntimeException ignored) {
            // não derrubar o agendador
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService sch;
        Subscription sub;
        synchronized (this) {
            sch = scheduler;
            sub = subscription;
            scheduler = null;
        }
        if (sch != null) sch.shutdownNow();
        if (sub != null) sub.close();
    }
}
