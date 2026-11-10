package dev.klleriston.netlab.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.server.TcpEchoServer;
import dev.klleriston.netlab.server.UdpEchoServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientModeTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private TcpEchoServer tcp;
    private UdpEchoServer udp;
    private Session session;

    @BeforeEach
    void setUp() throws IOException {
        tcp = new TcpEchoServer(0, line -> { });
        udp = new UdpEchoServer(0, line -> { });
        tcp.start();
        udp.start();
        session = new Session(new Args.ClientArgs(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), tcp.port(), udp.port(), 0));
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        tcp.close();
        udp.close();
    }

    @Test
    void runsScriptedSessionAndQuits() throws Exception {
        session.awaitOutput("Painel: http://");
        session.send("impair preset=wifi-ruim\nrun lifecycle closed-port=" + closedPort() + "\n");
        session.awaitOutput("ExperimentFinished");
        session.send("quit\n");
        session.awaitExit();

        String out = session.output();
        assertTrue(out.contains("ImpairmentChanged"), out);
        assertTrue(out.contains("\"lossPercent\":5"), out);
        assertTrue(out.contains("ExperimentStarted"), out);
        // porta filtrada por firewall (ex.: Windows Defender no CI) dá timeout em vez de recusa
        assertTrue(out.contains("ConnectionRefused") || out.contains("ConnectTimeout"), out);
        assertTrue(out.contains("netlab> "), out);
        assertFalse(out.contains("PacketSent"), out);
        assertFalse(out.contains("MetricsSnapshot"), out);
    }

    @Test
    void invalidCommandPrintsUsageAndContinues() throws Exception {
        session.send("impair loss=150\nrun foo\nstop\nhelp\n");
        session.awaitOutput("uso: impair");
        session.awaitOutput("lifecycle, reliability, latency, throughput");
        session.awaitOutput("nenhum experimento em andamento");
        session.awaitOutput("udp-rate=20mbps");
        session.send("impair off\n");
        session.awaitOutput("ImpairmentChanged");
        session.send("quit\n");
        session.awaitExit();
    }

    @Test
    void runWhileRunningIsRefused() throws Exception {
        session.send("run reliability count=1000 rate=10\nrun latency\n");
        session.awaitOutput("já existe um experimento em andamento");
        session.send("stop\n");
        session.awaitOutput("ExperimentAborted");
        session.send("quit\n");
        session.awaitExit();
    }

    @Test
    void eofStopsRunningExperimentAndExits() throws Exception {
        session.send("run reliability count=1000 rate=10\n");
        session.awaitOutput("ExperimentStarted");
        session.closeInput();
        session.awaitExit();
        assertTrue(session.output().contains("ExperimentAborted"), session.output());
    }

    @Test
    void interruptStopsOnlyOncePerRun() throws Exception {
        session.awaitOutput("Painel: http://");
        assertFalse(session.mode.interruptExperiment(), "ocioso: nada a interromper");
        session.send("run reliability count=1000 rate=10\n");
        session.awaitOutput("ExperimentStarted");
        assertTrue(session.mode.interruptExperiment(), "primeiro Ctrl+C interrompe o run");
        assertFalse(session.mode.interruptExperiment(), "segundo Ctrl+C com stop pendente encerra o cliente");
        session.awaitOutput("ExperimentAborted");
        assertFalse(session.mode.interruptExperiment(), "ocioso depois do abort");
        session.send("quit\n");
        session.awaitExit();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** ClientMode numa thread própria, com entrada por pipe e saída capturada. */
    private static final class Session implements AutoCloseable {

        private final PipedOutputStream input = new PipedOutputStream();
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        final ClientMode mode;

        Session(Args.ClientArgs args) throws IOException {
            PipedInputStream in = new PipedInputStream(input, 64 * 1024);
            PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
            mode = new ClientMode(args, in, out);
            thread = Thread.ofPlatform().name("client-mode").start(() -> {
                try {
                    mode.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }

        void send(String text) throws IOException {
            input.write(text.getBytes(StandardCharsets.UTF_8));
            input.flush();
        }

        void closeInput() throws IOException {
            input.close();
        }

        String output() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        void awaitOutput(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!output().contains(expected)) {
                if (System.nanoTime() > deadline) fail("saída sem \"" + expected + "\":\n" + output());
                Thread.sleep(10);
            }
        }

        void awaitExit() throws InterruptedException {
            thread.join(WAIT.toMillis());
            assertFalse(thread.isAlive(), "ClientMode não terminou:\n" + output());
            assertNull(failure.get(), () -> "ClientMode falhou: " + failure.get());
        }

        @Override
        public void close() throws Exception {
            if (thread.isAlive()) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // já fechado
                }
                thread.join(WAIT.toMillis());
            }
        }
    }
}
