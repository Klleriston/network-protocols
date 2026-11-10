package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReliabilityExperimentTest {

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

    private String run(ImpairmentProfile profile, Map<String, String> params) {
        ExperimentRunner runner = lab.runner(() -> profile);
        assertTrue(runner.start(new ReliabilityExperiment(), params));
        Events.ExperimentFinished finished = lab.awaitEvent(Events.ExperimentFinished.class, WAIT);
        assertEquals("reliability", finished.name());
        return finished.summary();
    }

    @Test
    void nameIsReliability() {
        assertEquals("reliability", new ReliabilityExperiment().name());
    }

    @Test
    void tcpDeliversAllInOrder() {
        String summary = run(ImpairmentProfile.NONE, Map.of("count", "200", "rate", "1000"));
        assertTrue(summary.contains("TCP: 200/200 entregues, 0 perdidos, 0 reordenados"), summary);
        assertTrue(summary.contains("UDP: 200/200 entregues, 0 perdidos"), summary);
    }

    @Test
    void udpWithFullLossLosesAll() {
        String summary = run(new ImpairmentProfile(100, 0, 0, 0), Map.of("count", "50"));
        assertTrue(summary.contains("UDP: 0/50 entregues, 50 perdidos, 0 reordenados, 0 tardios"), summary);
    }

    @Test
    void impairmentDoesNotAffectTcp() {
        String summary = run(new ImpairmentProfile(100, 0, 0, 0), Map.of("count", "200", "rate", "1000"));
        assertTrue(summary.contains("TCP: 200/200 entregues, 0 perdidos, 0 reordenados"), summary);
    }
}
