package dev.klleriston.netlab.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class DashboardServerTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private EventBus bus;
    private DashboardServer server;

    @BeforeEach
    void setUp() throws IOException {
        bus = new EventBus();
        server = new DashboardServer(bus, 0, clock::get);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        bus.close();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static Events.MetricsSnapshot snapshot(Protocol p, long sent) {
        return new Events.MetricsSnapshot(sent, p, sent, sent, 0, 0, 0, 0.0, 1, 2, 3, 4, 5, 6, 0);
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timeout aguardando: " + what);
            Thread.sleep(5);
        }
    }

    /** Evento SSE lido do stream. */
    record Sse(String event, String data) { }

    /** Cliente SSE com thread leitora; toda leitura tem timeout. */
    final class SseClient implements AutoCloseable {
        private final InputStream body;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        SseClient() throws Exception {
            HttpResponse<InputStream> resp = http.send(
                    HttpRequest.newBuilder(uri("/events")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, resp.statusCode());
            assertEquals("text/event-stream",
                    resp.headers().firstValue("Content-Type").orElse(""));
            assertEquals("no-cache", resp.headers().firstValue("Cache-Control").orElse(""));
            body = resp.body();
            Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) lines.add(line);
                } catch (IOException ignored) {
                    // stream fechado pelo teste
                }
            });
        }

        Sse next() throws InterruptedException {
            String event = null;
            String data = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (line == null) fail("timeout aguardando evento SSE");
                if (line.startsWith("event: ")) event = line.substring(7);
                else if (line.startsWith("data: ")) data = line.substring(6);
                else if (line.isEmpty() && event != null) return new Sse(event, data);
            }
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    @Test
    void servesIndexHtml() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("text/html; charset=utf-8", resp.headers().firstValue("Content-Type").orElse(""));
        assertTrue(resp.body().contains("<title>netlab</title>"), resp.body());
    }

    @Test
    void indexWiresEventSourceAndBothProtocolCards() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(uri("/")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        for (String expected : new String[] {"new EventSource(\"/events\")", "id=\"card-tcp\"",
                "id=\"card-udp\"", "id=\"rtt-chart\"", "id=\"timeline\"", "id=\"impairment\""}) {
            assertTrue(resp.body().contains(expected), "missing " + expected);
        }
    }

    @Test
    void unknownPathIs404AndNonGetIs405() throws Exception {
        HttpResponse<String> notFound = http.send(
                HttpRequest.newBuilder(uri("/nada")).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, notFound.statusCode());
        HttpResponse<String> post = http.send(
                HttpRequest.newBuilder(uri("/")).timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void streamsLifecycleEventsAsSse() throws Exception {
        try (SseClient c = new SseClient()) {
            Events.ExperimentStarted e = new Events.ExperimentStarted(7, "latência");
            bus.publish(e);
            Sse got = c.next();
            assertEquals("ExperimentStarted", got.event());
            assertEquals(e.toJson(), got.data());
        }
    }

    @Test
    void doesNotForwardPacketSentOrEchoed() throws Exception {
        try (SseClient c = new SseClient()) {
            bus.publish(new Events.PacketSent(1, Protocol.UDP, 1, 64));
            bus.publish(new Events.PacketEchoed(2, Protocol.UDP, 1, 1000, 64));
            bus.publish(new Events.PacketLost(3, Protocol.UDP, 2));
            assertEquals("PacketLost", c.next().event());
        }
    }

    @Test
    void newConnectionReceivesLatestSnapshotsFirst() throws Exception {
        Events.MetricsSnapshot tcp = snapshot(Protocol.TCP, 11);
        Events.MetricsSnapshot udp = snapshot(Protocol.UDP, 22);
        Events.ImpairmentChanged imp = new Events.ImpairmentChanged(3, 10, 20, 5, 1);
        bus.publish(udp);
        bus.publish(snapshot(Protocol.TCP, 1));
        bus.publish(tcp);
        bus.publish(imp);
        await(() -> tcp.equals(server.latestSnapshot(Protocol.TCP))
                && udp.equals(server.latestSnapshot(Protocol.UDP))
                && imp.equals(server.latestImpairment()), "snapshots retidos");
        try (SseClient c = new SseClient()) {
            assertEquals(new Sse("MetricsSnapshot", tcp.toJson()), c.next());
            assertEquals(new Sse("MetricsSnapshot", udp.toJson()), c.next());
            assertEquals(new Sse("ImpairmentChanged", imp.toJson()), c.next());
        }
    }

    @Test
    void disconnectingClientDoesNotBlockPublishers() throws Exception {
        SseClient first = new SseClient();
        bus.publish(new Events.ExperimentStarted(1, "throughput"));
        assertEquals("ExperimentStarted", first.next().event());
        first.close();

        long t0 = System.nanoTime();
        for (int i = 0; i < 10_000; i++) bus.publish(snapshot(Protocol.TCP, i));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 500, "publish levou " + elapsedMs + " ms");

        long[] next = {10_000};
        await(() -> {
            bus.publish(snapshot(Protocol.TCP, next[0]++));
            return server.connectionCount() == 0;
        }, "remoção do assinante desconectado");

        Events.MetricsSnapshot latest = snapshot(Protocol.TCP, next[0]);
        bus.publish(latest);
        await(() -> latest.equals(server.latestSnapshot(Protocol.TCP)), "snapshot mais recente retido");
        try (SseClient c = new SseClient()) {
            assertEquals(new Sse("MetricsSnapshot", latest.toJson()), c.next());
        }
    }

    @Test
    void rateLimitsLossEventsAndReportsSuppressed() throws Exception {
        try (SseClient c = new SseClient()) {
            for (int i = 0; i < 60; i++) bus.publish(new Events.PacketLost(i, Protocol.UDP, i));
            bus.publish(new Events.ExperimentStarted(100, "a"));
            for (int i = 0; i < 50; i++) assertEquals("PacketLost", c.next().event());
            assertEquals("ExperimentStarted", c.next().event());

            clock.addAndGet(1_000_000_000L);
            bus.publish(new Events.ExperimentStarted(200, "b"));
            Sse suppressed = c.next();
            assertEquals("Suppressed", suppressed.event());
            assertNotNull(suppressed.data());
            assertTrue(suppressed.data().contains("\"count\":10"), suppressed.data());
            assertEquals("ExperimentStarted", c.next().event());
        }
    }

}
