package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UdpEchoSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private LoopbackLab lab;
    private ExperimentContext ctx;

    @BeforeEach
    void setUp() {
        lab = LoopbackLab.start();
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.closeTracked();
        lab.close();
    }

    private UdpEchoSession session(ImpairmentProfile profile, Duration lossTimeout) throws IOException {
        ctx = new ExperimentContext(lab.target(), lab.bus(), System::nanoTime, () -> profile, new Random(42),
                Map.of());
        UdpEchoSession s = new UdpEchoSession(ctx, ctx.openUdp(), lossTimeout);
        s.start();
        return s;
    }

    private long countEvents(Predicate<Event> p) {
        return lab.events().stream().filter(p).count();
    }

    /** Espera até a condição valer; falha se não valer dentro do prazo. */
    private static void await(String what, LongSupplier actual, long expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (actual.getAsLong() != expected) {
            if (System.nanoTime() >= deadline) {
                fail(what + ": esperado " + expected + ", obtido " + actual.getAsLong());
            }
            Thread.sleep(10);
        }
    }

    @Test
    void allEchoedWithoutImpairment() throws Exception {
        try (UdpEchoSession s = session(ImpairmentProfile.NONE, Duration.ofSeconds(1))) {
            for (int seq = 1; seq <= 100; seq++) s.send(seq, 64);
            assertTrue(s.awaitDrained(WAIT));
            assertEquals(100, s.echoed());
            assertEquals(0, s.lost());
            assertEquals(0, s.late());
        }
        await("PacketEchoed UDP", () -> countEvents(e -> e instanceof Events.PacketEchoed p
                && p.protocol() == Protocol.UDP && p.bytes() == 84 && p.rttNanos() >= 0), 100, WAIT);
        assertEquals(100, countEvents(e -> e instanceof Events.PacketSent p && p.protocol() == Protocol.UDP
                && p.bytes() == 84));
    }

    @Test
    void allLostAtFullLoss() throws Exception {
        try (UdpEchoSession s = session(new ImpairmentProfile(100, 0, 0, 0), Duration.ofMillis(200))) {
            for (int seq = 1; seq <= 100; seq++) s.send(seq, 64);
            assertTrue(s.awaitDrained(WAIT));
            assertEquals(100, s.lost());
            assertEquals(0, s.echoed());
        }
        await("PacketLost UDP", () -> countEvents(e -> e instanceof Events.PacketLost p
                && p.protocol() == Protocol.UDP), 100, WAIT);
    }

    @Test
    void detectsReordering() throws Exception {
        try (UdpEchoSession s = session(new ImpairmentProfile(0, 0, 0, 50), Duration.ofSeconds(1))) {
            for (int seq = 1; seq <= 200; seq++) s.send(seq, 64);
            assertTrue(s.awaitDrained(WAIT));
            assertEquals(200, s.echoed());
            assertTrue(s.reordered() > 0, "nenhuma reordenação detectada");
        }
        assertTrue(lab.events().stream().anyMatch(e -> e instanceof Events.PacketReordered r
                && r.protocol() == Protocol.UDP && r.seq() < r.highestSeenSeq()));
    }

    @Test
    void echoAfterTimeoutCountsAsLateOnly() throws Exception {
        try (UdpEchoSession s = session(new ImpairmentProfile(0, 300, 0, 0), Duration.ofMillis(100))) {
            s.send(1, 64);
            assertTrue(s.awaitDrained(WAIT));
            assertEquals(1, s.lost());
            await("late()", s::late, 1, WAIT);
            assertEquals(0, s.echoed());
            assertEquals(1, s.lost());
        }
        Events.PacketLate late = lab.awaitEvent(Events.PacketLate.class, WAIT);
        assertEquals(1, late.seq());
        assertEquals(Protocol.UDP, late.protocol());
        assertEquals(1, countEvents(e -> e instanceof Events.PacketLost));
        assertEquals(0, countEvents(e -> e instanceof Events.PacketEchoed));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        UdpEchoSession s = session(ImpairmentProfile.NONE, Duration.ofSeconds(1));
        s.close();
        s.close();
    }
}
