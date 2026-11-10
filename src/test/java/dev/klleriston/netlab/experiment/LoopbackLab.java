package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.fail;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import dev.klleriston.netlab.server.TcpEchoServer;
import dev.klleriston.netlab.server.UdpEchoServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Laboratório em loopback: servidores de echo em portas efêmeras, um EventBus e um coletor de eventos. */
final class LoopbackLab implements AutoCloseable {

    private static final int COLLECTOR_CAPACITY = 1_000_000;

    private final TcpEchoServer tcp;
    private final UdpEchoServer udp;
    private final Target target;
    private final EventBus bus = new EventBus();
    private final List<Event> events = new ArrayList<>();
    private final List<ExperimentRunner> runners = new CopyOnWriteArrayList<>();

    private LoopbackLab(TcpEchoServer tcp, UdpEchoServer udp, Target target) {
        this.tcp = tcp;
        this.udp = udp;
        this.target = target;
        bus.subscribe("lab-collector", COLLECTOR_CAPACITY, e -> {
            synchronized (events) {
                events.add(e);
            }
        });
    }

    static LoopbackLab start() {
        TcpEchoServer tcp = new TcpEchoServer(0, line -> { });
        UdpEchoServer udp = new UdpEchoServer(0, line -> { });
        try {
            tcp.start();
            udp.start();
            InetAddress host = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            return new LoopbackLab(tcp, udp, new Target(host, tcp.port(), udp.port()));
        } catch (IOException e) {
            tcp.close();
            udp.close();
            throw new UncheckedIOException(e);
        }
    }

    Target target() {
        return target;
    }

    EventBus bus() {
        return bus;
    }

    /** Cópia dos eventos coletados até agora, na ordem de publicação. */
    List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Primeiro evento do tipo; falha o teste se não chegar a tempo. */
    <T extends Event> T awaitEvent(Class<T> type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int scanned = 0;
        while (true) {
            synchronized (events) {
                for (; scanned < events.size(); scanned++) {
                    Event e = events.get(scanned);
                    if (type.isInstance(e)) return type.cast(e);
                }
            }
            if (System.nanoTime() >= deadline) {
                fail("evento " + type.getSimpleName() + " não chegou em " + timeout.toMillis() + " ms; recebidos: "
                        + events().stream().map(Event::type).toList());
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fail("interrompido esperando " + type.getSimpleName());
            }
        }
    }

    /** Runner ligado a este laboratório, com Random de semente 42. */
    ExperimentRunner runner(Supplier<ImpairmentProfile> impairment) {
        ExperimentRunner r = new ExperimentRunner(bus, System::nanoTime, impairment, new Random(42), target);
        runners.add(r);
        return r;
    }

    /** Derruba os servidores de echo (clientes TCP conectados recebem FIN ou RST). */
    void killServers() {
        tcp.close();
        udp.close();
    }

    @Override
    public void close() {
        for (ExperimentRunner r : runners) {
            r.stop();
            r.awaitIdle(Duration.ofSeconds(2));
        }
        killServers();
        bus.close();
    }
}
