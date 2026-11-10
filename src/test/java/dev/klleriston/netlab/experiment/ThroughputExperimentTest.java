package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThroughputExperimentTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private LoopbackLab lab;

    @BeforeEach
    void setUp() {
        lab = LoopbackLab.start();
    }

    @AfterEach
    void tearDown() {
        lab.close();
    }

    private long count(Class<?> type, Protocol protocol) {
        return lab.events().stream().filter(e -> switch (e) {
            case Events.PacketSent p -> type == Events.PacketSent.class && p.protocol() == protocol;
            case Events.PacketEchoed p -> type == Events.PacketEchoed.class && p.protocol() == protocol;
            default -> false;
        }).count();
    }

    @Test
    void nameIsThroughput() {
        assertEquals("throughput", new ThroughputExperiment().name());
    }

    @Test
    void throughputCountsEncodedFrameBytesLikeTheDashboard() {
        // 1000 frames de (1000 + 20 de cabeçalho) bytes em 1 s = 8,16 Mbit/s
        assertEquals(8.16, ThroughputExperiment.mbps(1000, 1000, 1_000_000_000L), 1e-9);
    }

    @Test
    void tcpPhaseRunsForRequestedSeconds() throws Exception {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        long t0 = System.nanoTime();
        assertTrue(runner.start(new ThroughputExperiment(), Map.of("seconds", "1", "udp-rate", "100kbps")));
        lab.awaitEvent(Events.ConnectionEstablished.class, WAIT);
        // a fase UDP começa depois do TCP: o primeiro PacketSent UDP marca o fim da fase TCP
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (count(Events.PacketSent.class, Protocol.UDP) == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        double tcpSeconds = (System.nanoTime() - t0) / 1e9;
        assertTrue(tcpSeconds >= 1.0 && tcpSeconds <= 2.5, "fase TCP levou " + tcpSeconds + " s");
        lab.awaitEvent(Events.ExperimentFinished.class, WAIT);
        assertTrue(count(Events.PacketEchoed.class, Protocol.TCP) > 0);
    }

    @Test
    void udpPhaseApproximatesTargetRate() {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        assertTrue(runner.start(new ThroughputExperiment(),
                Map.of("seconds", "1", "udp-rate", "1mbps", "size", "1000")));
        Events.ExperimentFinished f = lab.awaitEvent(Events.ExperimentFinished.class, WAIT);
        long sent = count(Events.PacketSent.class, Protocol.UDP);
        assertTrue(sent >= 100 && sent <= 150, "enviados UDP: " + sent);
        assertTrue(f.summary().matches("TCP: [\\d.,]+ Mbit/s \\| UDP: alvo [\\d.,]+ Mbit/s, entregue [\\d.,]+ Mbit/s, "
                + "perda [\\d.,]+%"), f.summary());
    }

    @Test
    void stopDuringTcpBlastAbortsQuickly() throws Exception {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        assertTrue(runner.start(new ThroughputExperiment(), Map.of("seconds", "30")));
        Thread.sleep(300);
        long t0 = System.nanoTime();
        runner.stop();
        lab.awaitEvent(Events.ExperimentAborted.class, Duration.ofSeconds(5));
        assertTrue((System.nanoTime() - t0) < 1_000_000_000L, "abortou em " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    @Test
    void invalidRateFails() {
        ExperimentRunner runner = lab.runner(() -> ImpairmentProfile.NONE);
        assertTrue(runner.start(new ThroughputExperiment(), Map.of("udp-rate", "rápido")));
        Events.ExperimentFailed f = lab.awaitEvent(Events.ExperimentFailed.class, WAIT);
        assertTrue(f.reason().contains("taxa inválida: rápido"), f.reason());
    }
}
