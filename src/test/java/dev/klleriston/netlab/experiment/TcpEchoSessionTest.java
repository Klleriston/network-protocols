package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.impair.ImpairmentProfile;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TcpEchoSessionTest {

    /** Servidor que aceita a conexão mas nunca ecoa: a leitura do cliente esgota o SO_TIMEOUT de 5 s. */
    @Test
    @Timeout(20)
    void readTimeoutMidSessionFailsWithReadTimeoutMessageNotFirewallHint() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        EventBus bus = new EventBus();
        try (ServerSocket silent = new ServerSocket(0, 1, loopback)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket s = silent.accept()) {
                    s.getInputStream().readAllBytes(); // lê e descarta até o cliente fechar
                } catch (IOException ignored) {
                    // servidor fechado pelo teste
                }
            });
            BlockingQueue<Event> events = new LinkedBlockingQueue<>();
            bus.subscribe("test", events::add);
            Target target = new Target(loopback, silent.getLocalPort(), 1);
            ExperimentRunner runner = new ExperimentRunner(bus, System::nanoTime, () -> ImpairmentProfile.NONE,
                    new Random(42), target);
            AtomicReference<IOException> thrown = new AtomicReference<>();
            assertTrue(runner.start(new Experiment() {
                public String name() { return "mudo"; }

                public String run(ExperimentContext ctx) throws Exception {
                    try (TcpEchoSession s = TcpEchoSession.connect(ctx, true)) {
                        s.roundTrip(1, 64);
                        return "recebeu echo inesperado";
                    } catch (IOException e) {
                        thrown.set(e);
                        throw e;
                    }
                }
            }, Map.of()));

            Events.ExperimentFailed failed = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (failed == null) {
                Event e = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                assertNotNull(e, "ExperimentFailed não chegou");
                assertFalse(e instanceof Events.ExperimentFinished, e.toJson());
                if (e instanceof Events.ExperimentFailed f) failed = f;
            }
            String remote = target.tcpRemote();
            assertTrue(failed.reason().contains("tempo de leitura esgotado (5 s) em " + remote), failed.reason());
            assertFalse(failed.reason().contains("Firewall"), failed.reason());
            assertInstanceOf(SocketTimeoutException.class, thrown.get().getCause());
            assertTrue(runner.awaitIdle(Duration.ofSeconds(2)));
            silent.close();
            acceptor.join(2000);
        } finally {
            bus.close();
        }
    }
}
