package dev.klleriston.netlab.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.EventBus;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.event.Subscription;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Painel HTTP: serve {@code /} (index.html) e {@code /events} (stream SSE dos eventos do bus).
 * Escuta em todas as interfaces para que o celular na rede local consiga conectar.
 */
public final class DashboardServer implements AutoCloseable {

    private static final int LOSS_EVENTS_PER_SECOND = 50;
    private static final long HEARTBEAT_SECONDS = 15;
    private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

    private final EventBus bus;
    private final int requestedPort;
    private final LongSupplier nanoClock;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connectionIds = new AtomicInteger();
    private volatile Events.MetricsSnapshot latestTcp;
    private volatile Events.MetricsSnapshot latestUdp;
    private volatile Events.ImpairmentChanged latestImpairment;
    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile Subscription ownSubscription;
    private volatile boolean closed;

    public DashboardServer(EventBus bus, int port, LongSupplier nanoClock) {
        this.bus = bus;
        this.requestedPort = port;
        this.nanoClock = nanoClock;
    }

    public void start() throws IOException {
        if (server != null) throw new IllegalStateException("já iniciado");
        // retém apenas o que o bus entrega; nunca chama MetricsAggregator.snapshot()
        ownSubscription = bus.subscribe("dashboard", this::retain);
        HttpServer s = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        s.setExecutor(executor);
        s.createContext("/", this::handle);
        s.start();
        server = s;
    }

    public int port() {
        HttpServer s = server;
        if (s == null) throw new IllegalStateException("servidor não iniciado");
        return s.getAddress().getPort();
    }

    private void retain(Event e) {
        switch (e) {
            case Events.MetricsSnapshot m when m.protocol() == Protocol.TCP -> latestTcp = m;
            case Events.MetricsSnapshot m when m.protocol() == Protocol.UDP -> latestUdp = m;
            case Events.ImpairmentChanged i -> latestImpairment = i;
            default -> { }
        }
    }

    Events.MetricsSnapshot latestSnapshot(Protocol p) {
        return switch (p) {
            case TCP -> latestTcp;
            case UDP -> latestUdp;
            case NONE -> null;
        };
    }

    Events.ImpairmentChanged latestImpairment() {
        return latestImpairment;
    }

    int connectionCount() {
        return connections.size();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            boolean known = path.equals("/") || path.equals("/events");
            if (!known) {
                sendEmpty(ex, 404);
            } else if (!ex.getRequestMethod().equals("GET")) {
                ex.getResponseHeaders().set("Allow", "GET");
                sendEmpty(ex, 405);
            } else if (path.equals("/")) {
                serveIndex(ex);
            } else {
                serveEvents(ex);
            }
        } catch (IOException | RuntimeException e) {
            ex.close();
            if (!closed) throw e;
        }
    }

    private static void sendEmpty(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private static void serveIndex(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard/index.html")) {
            if (in == null) {
                sendEmpty(ex, 500);
                return;
            }
            body = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void serveEvents(HttpExchange ex) throws IOException {
        Connection c = new Connection(ex, connectionIds.incrementAndGet());
        connections.add(c);
        if (closed) c.close();
        c.run();
    }

    @Override
    public void close() {
        closed = true;
        for (Connection c : connections) c.close();
        HttpServer s = server;
        if (s != null) s.stop(0);
        Subscription own = ownSubscription;
        if (own != null) own.close();
        ExecutorService e = executor;
        if (e != null) e.shutdownNow();
    }

    /** Uma conexão SSE: assinatura própria do bus, limitador próprio e heartbeat. */
    private final class Connection {
        private final HttpExchange exchange;
        private final int id;
        private final ReentrantLock writeLock = new ReentrantLock();
        private final CountDownLatch done = new CountDownLatch(1);
        private final SseRateLimiter limiter = new SseRateLimiter(LOSS_EVENTS_PER_SECOND, nanoClock);
        private volatile OutputStream out;
        private volatile Subscription subscription;
        private volatile boolean connectionClosed;

        Connection(HttpExchange exchange, int id) {
            this.exchange = exchange;
            this.id = id;
        }

        /** Roda na virtual thread do exchange até a conexão terminar. */
        void run() {
            writeLock.lock();
            try {
                // assina antes de tudo: nenhum evento publicado depois dos headers é perdido;
                // o handler espera o lock, então os retidos saem primeiro
                subscription = bus.subscribe("dashboard-sse-" + id, this::onEvent);
                if (connectionClosed) subscription.close();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, 0);
                out = exchange.getResponseBody();
                for (Event e : new Event[] {latestTcp, latestUdp, latestImpairment}) {
                    if (e != null) write(SseFormat.format(e).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                close();
                return;
            } finally {
                writeLock.unlock();
            }
            try {
                while (!done.await(HEARTBEAT_SECONDS, TimeUnit.SECONDS)) {
                    writeLocked(PING);
                }
            } catch (InterruptedException e) {
                close();
            }
        }

        private void onEvent(Event e) {
            // eventos por pacote já entram nos contadores do MetricsSnapshot; PacketLost/Reordered seguem (com limite)
            if (e instanceof Events.PacketSent || e instanceof Events.PacketEchoed || e instanceof Events.PacketLate) {
                return;
            }
            long suppressed = limiter.takeSuppressed();
            StringBuilder msg = new StringBuilder();
            if (suppressed > 0) {
                msg.append(SseFormat.format(new Events.Suppressed(nanoClock.getAsLong(), suppressed)));
            }
            boolean lossy = e instanceof Events.PacketLost || e instanceof Events.PacketReordered;
            if (!lossy || limiter.tryAcquire()) msg.append(SseFormat.format(e));
            if (!msg.isEmpty()) writeLocked(msg.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void writeLocked(byte[] bytes) {
            writeLock.lock();
            try {
                write(bytes);
            } catch (IOException e) {
                close();
            } finally {
                writeLock.unlock();
            }
        }

        private void write(byte[] bytes) throws IOException {
            if (connectionClosed) throw new IOException("conexão SSE fechada");
            out.write(bytes);
            out.flush();
        }

        /** Idempotente; não toma o lock para não esperar uma escrita travada. */
        void close() {
            if (connectionClosed) return;
            connectionClosed = true;
            connections.remove(this);
            Subscription s = subscription;
            if (s != null) s.close();
            done.countDown();
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // socket já morto
            }
        }
    }
}
