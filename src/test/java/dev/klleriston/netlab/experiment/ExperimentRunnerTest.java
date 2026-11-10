package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.impair.ImpairedDatagramSocket;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExperimentRunnerTest {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private LoopbackLab lab;
    private ExperimentRunner runner;

    @BeforeEach
    void setUp() {
        lab = LoopbackLab.start();
        runner = lab.runner(() -> ImpairmentProfile.NONE);
    }

    @AfterEach
    void tearDown() {
        lab.close();
    }

    private static Experiment experiment(String name, Body body) {
        return new Experiment() {
            public String name() { return name; }
            public String run(ExperimentContext ctx) throws Exception { return body.run(ctx); }
        };
    }

    @FunctionalInterface
    private interface Body {
        String run(ExperimentContext ctx) throws Exception;
    }

    @Test
    void publishesStartedThenFinishedWithSummary() {
        assertTrue(runner.start(experiment("ok", ctx -> "resumo " + ctx.param("k", "?")), Map.of("k", "v")));
        Events.ExperimentFinished finished = lab.awaitEvent(Events.ExperimentFinished.class, WAIT);
        assertEquals("ok", finished.name());
        assertEquals("resumo v", finished.summary());

        List<Event> events = lab.events();
        assertInstanceOf(Events.ExperimentStarted.class, events.get(0));
        assertEquals("ok", ((Events.ExperimentStarted) events.get(0)).name());
        assertEquals(1, events.stream().filter(e -> e instanceof Events.ExperimentFinished
                || e instanceof Events.ExperimentAborted || e instanceof Events.ExperimentFailed).count());
        assertTrue(runner.awaitIdle(WAIT));
        assertFalse(runner.isRunning());
    }

    @Test
    void runsOnPlatformThreadNamedAfterExperiment() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        runner.start(experiment("nome", ctx -> {
            thread.set(Thread.currentThread());
            return "";
        }), Map.of());
        assertTrue(runner.awaitIdle(WAIT));
        assertEquals("experiment-nome", thread.get().getName());
        assertFalse(thread.get().isVirtual());
    }

    @Test
    void refusesSecondRunWhileRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(runner.start(experiment("bloqueado", ctx -> {
            release.await();
            return "fim";
        }), Map.of()));
        assertTrue(runner.isRunning());
        assertFalse(runner.start(experiment("outro", ctx -> "x"), Map.of()));
        release.countDown();
        assertTrue(runner.awaitIdle(WAIT));
        assertFalse(runner.isRunning());
        assertEquals("fim", lab.awaitEvent(Events.ExperimentFinished.class, WAIT).summary());
        assertTrue(lab.events().stream().noneMatch(e -> e instanceof Events.ExperimentStarted s
                && s.name().equals("outro")));
    }

    @Test
    void stopWithNothingRunningReturnsFalse() {
        assertFalse(runner.stop());
        assertFalse(runner.isRunning());
        assertTrue(runner.awaitIdle(Duration.ZERO));
    }

    @Test
    void stopAbortsBlockedExperimentWithinOneSecond() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicReference<ImpairedDatagramSocket> socket = new AtomicReference<>();
        runner.start(experiment("bloqueia", ctx -> {
            ImpairedDatagramSocket s = ctx.openUdp();
            socket.set(s);
            s.setSoTimeout(0);
            blocked.countDown();
            s.receive(new DatagramPacket(new byte[1500], 1500));
            return "não deveria terminar";
        }), Map.of());
        assertTrue(blocked.await(3, TimeUnit.SECONDS));
        Thread.sleep(100); // garante que receive() já está bloqueado

        long t0 = System.nanoTime();
        assertTrue(runner.stop());
        Events.ExperimentAborted aborted = lab.awaitEvent(Events.ExperimentAborted.class, Duration.ofSeconds(1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals("bloqueia", aborted.name());
        assertTrue(elapsedMs < 1000, "abortou em " + elapsedMs + " ms");
        assertFalse(runner.isRunning());
        assertTrue(socket.get().delegate().isClosed());
        assertTrue(lab.events().stream().noneMatch(e -> e instanceof Events.ExperimentFinished
                || e instanceof Events.ExperimentFailed));
    }

    @Test
    void unreachableServerFailsWithFirewallHintAndRunnerStaysUsable() {
        assertTrue(runner.start(experiment("sem-server", ctx -> {
            throw new ConnectException("Connection refused");
        }), Map.of()));
        Events.ExperimentFailed failed = lab.awaitEvent(Events.ExperimentFailed.class, WAIT);
        assertTrue(failed.reason().contains("Firewall do Windows"), failed.reason());
        assertTrue(failed.reason().contains(lab.target().tcpRemote()), failed.reason());
        assertTrue(runner.awaitIdle(WAIT));

        assertTrue(runner.start(experiment("depois", ctx -> "ok"), Map.of()));
        assertEquals("ok", lab.awaitEvent(Events.ExperimentFinished.class, WAIT).summary());
    }

    @Test
    void unexpectedExceptionBecomesFailedWithMessage() {
        runner.start(experiment("quebra", ctx -> {
            throw new IllegalStateException("boom");
        }), Map.of());
        Events.ExperimentFailed failed = lab.awaitEvent(Events.ExperimentFailed.class, WAIT);
        assertEquals("quebra", failed.name());
        assertEquals("java.lang.IllegalStateException: boom", failed.reason());
    }

    @Test
    void nonIntegerParamFailsTheExperiment() {
        runner.start(experiment("param", ctx -> "n=" + ctx.intParam("count", 5)), Map.of("count", "abc"));
        Events.ExperimentFailed failed = lab.awaitEvent(Events.ExperimentFailed.class, WAIT);
        assertTrue(failed.reason().contains("parâmetro count deve ser inteiro: abc"), failed.reason());
    }

    @Test
    void intParamUsesDefaultWhenMissing() {
        runner.start(experiment("param", ctx -> "n=" + ctx.intParam("count", 5) + "," + ctx.intParam("x", 1)),
                Map.of("x", "7"));
        assertEquals("n=5,7", lab.awaitEvent(Events.ExperimentFinished.class, WAIT).summary());
    }

    @Test
    void trackedResourcesAreClosedWhenRunEnds() {
        AtomicReference<ImpairedDatagramSocket> socket = new AtomicReference<>();
        runner.start(experiment("rastreia", ctx -> {
            socket.set(ctx.openUdp());
            return "ok";
        }), Map.of());
        assertTrue(runner.awaitIdle(WAIT));
        assertTrue(socket.get().delegate().isClosed());
    }
}
