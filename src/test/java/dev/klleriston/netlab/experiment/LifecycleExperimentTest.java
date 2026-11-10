package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LifecycleExperimentTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private LoopbackLab lab;

    @BeforeEach
    void setUp() {
        lab = LoopbackLab.start();
    }

    @AfterEach
    void tearDown() {
        lab.close();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void timelineContainsHandshakeFinRstAndRefusal() throws IOException {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        int closed = closedPort();
        assertTrue(runner.start(new LifecycleExperiment(), Map.of("closed-port", String.valueOf(closed))));

        Events.ExperimentFinished finished = lab.awaitEvent(Events.ExperimentFinished.class, WAIT);
        assertEquals("lifecycle", finished.name());
        assertTrue(finished.summary().matches("handshake TCP: \\d+ µs"), finished.summary());

        List<Event> events = lab.events();
        List<Predicate<Event>> expected = List.of(
                e -> e instanceof Events.ConnectionEstablished c && c.protocol() == Protocol.TCP,
                e -> e instanceof Events.ConnectionClosed c && c.graceful(),
                e -> e instanceof Events.ConnectionEstablished c && c.protocol() == Protocol.TCP,
                e -> e instanceof Events.ConnectionClosed c && !c.graceful(),
                e -> e instanceof Events.ConnectionRefused r && r.protocol() == Protocol.TCP
                        && r.remote().equals("127.0.0.1:" + closed),
                e -> e instanceof Events.PacketEchoed p && p.protocol() == Protocol.UDP,
                e -> e instanceof Events.ExperimentFinished);
        int i = 0;
        for (Event e : events) {
            if (i < expected.size() && expected.get(i).test(e)) i++;
        }
        assertEquals(expected.size(), i, "sequência incompleta (parou no passo " + i + "): "
                + events.stream().map(Event::toJson).toList());
        assertTrue(events.stream().noneMatch(e -> e instanceof Events.ExperimentFailed));
        assertTrue(events.stream().anyMatch(e -> (e instanceof Events.UdpPortUnreachable)
                || (e instanceof Events.PacketLost l && l.protocol() == Protocol.UDP)));
    }

    @Test
    void tcpResetFromPeerIsReported() {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        runner.start(new Experiment() {
            public String name() { return "peer-close"; }

            public String run(ExperimentContext ctx) throws Exception {
                try (TcpEchoSession s = TcpEchoSession.connect(ctx, true)) {
                    s.roundTrip(1, 64);
                    lab.killServers();
                    try {
                        s.receive();
                        return "recebeu algo inesperado";
                    } catch (IOException expected) {
                        return "fechado: " + expected.getClass().getSimpleName();
                    }
                }
            }
        }, Map.of());

        Events.ExperimentFinished finished = lab.awaitEvent(Events.ExperimentFinished.class, WAIT);
        assertTrue(finished.summary().startsWith("fechado"), finished.summary());
        assertTrue(lab.events().stream().anyMatch(e -> e instanceof Events.ConnectionReset
                || (e instanceof Events.ConnectionClosed c && c.graceful())), lab.events().toString());
    }
}
