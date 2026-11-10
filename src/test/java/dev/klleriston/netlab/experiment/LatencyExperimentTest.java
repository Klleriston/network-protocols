package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LatencyExperimentTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private LoopbackLab lab;

    @BeforeEach
    void setUp() {
        lab = LoopbackLab.start();
    }

    @AfterEach
    void tearDown() {
        lab.close();
    }

    private Event runAndAwaitTerminal(Map<String, String> params, Class<? extends Event> terminal) {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        assertTrue(runner.start(new LatencyExperiment(), params));
        return lab.awaitEvent(terminal, WAIT);
    }

    private long echoes(Protocol protocol) {
        return lab.events().stream()
                .filter(e -> e instanceof Events.PacketEchoed p && p.protocol() == protocol)
                .count();
    }

    @Test
    void nameIsLatency() {
        assertEquals("latency", new LatencyExperiment().name());
    }

    @Test
    void producesOneEchoPerSamplePerProtocol() {
        runAndAwaitTerminal(Map.of("samples", "20"), Events.ExperimentFinished.class);
        // o coletor é assíncrono: espera o fim do run e confere
        long deadline = System.nanoTime() + WAIT.toNanos();
        while ((echoes(Protocol.TCP) < 20 || echoes(Protocol.UDP) < 20) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(20, echoes(Protocol.TCP));
        assertEquals(20, echoes(Protocol.UDP));
    }

    @Test
    void nagleOnDisablesTcpNoDelay() throws Exception {
        ExperimentContext ctx = new ExperimentContext(lab.target(), lab.bus(), System::nanoTime,
                () -> ImpairmentProfile.NONE, new Random(1), Map.of());
        try (TcpEchoSession on = TcpEchoSession.connect(ctx, false);
             TcpEchoSession off = TcpEchoSession.connect(ctx, true)) {
            assertFalse(on.tcpNoDelay());
            assertTrue(off.tcpNoDelay());
        }
        Events.ExperimentFinished finished = (Events.ExperimentFinished)
                runAndAwaitTerminal(Map.of("samples", "5", "nagle", "on"), Events.ExperimentFinished.class);
        assertTrue(finished.summary().contains("nagle=on"), finished.summary());
    }

    @Test
    void summaryReportsAvgAndP95PerProtocol() {
        Events.ExperimentFinished finished = (Events.ExperimentFinished)
                runAndAwaitTerminal(Map.of("samples", "20"), Events.ExperimentFinished.class);
        assertTrue(finished.summary().matches(
                "TCP: avg \\d+ µs, p95 \\d+ µs .* UDP: avg \\d+ µs, p95 \\d+ µs.*"), finished.summary());
    }

    @Test
    void invalidNagleValueFails() {
        Events.ExperimentFailed failed = (Events.ExperimentFailed)
                runAndAwaitTerminal(Map.of("nagle", "talvez"), Events.ExperimentFailed.class);
        assertTrue(failed.reason().contains("nagle deve ser on ou off"), failed.reason());
        assertEquals(0, echoes(Protocol.TCP));
    }
}
