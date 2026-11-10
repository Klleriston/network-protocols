package dev.klleriston.netlab.impair;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Envolve um DatagramSocket aplicando perda, atraso, jitter e reordenação nos dois sentidos. */
public final class ImpairedDatagramSocket implements AutoCloseable {
    private static final int MAX_DATAGRAM = 65_536;

    private record Incoming(byte[] data, InetAddress address, int port) {}

    private record Failure(IOException error) {}

    private static final Object CLOSED = new Object();

    private final DatagramSocket delegate;
    private final Supplier<ImpairmentProfile> profile;
    private final ImpairmentEngine engine;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("impair-scheduler"));
    private final LinkedBlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
    private volatile int timeoutMs = 0;
    private volatile boolean closed = false;
    private Thread reader;

    public ImpairedDatagramSocket(DatagramSocket delegate, Supplier<ImpairmentProfile> profile, ImpairmentEngine engine) {
        this.delegate = delegate;
        this.profile = profile;
        this.engine = engine;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public DatagramSocket delegate() {
        return delegate;
    }

    public void setSoTimeout(int ms) {
        if (ms < 0) throw new IllegalArgumentException("timeout negativo: " + ms);
        this.timeoutMs = ms;
    }

    public void send(DatagramPacket packet) throws IOException {
        if (closed) throw new SocketException("Socket closed");
        Decision d = engine.decide(profile.get());
        if (d.drop()) return;
        byte[] copy = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
        DatagramPacket out = new DatagramPacket(copy, copy.length);
        if (packet.getAddress() != null) {
            out.setAddress(packet.getAddress());
            out.setPort(packet.getPort());
        }
        if (d.delayMillis() <= 0) {
            delegate.send(out);
        } else {
            try {
                scheduler.schedule(() -> {
                    try {
                        if (!closed) delegate.send(out);
                    } catch (PortUnreachableException e) {
                        inbox.offer(new Failure(e));
                    } catch (IOException ignored) {
                        // pacote enviado em segundo plano: erro equivale a perda na rede
                    }
                }, d.delayMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                throw new SocketException("Socket closed");
            }
        }
    }

    public void receive(DatagramPacket p) throws IOException {
        startReader();
        long deadline = timeoutMs == 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (closed) throw new SocketException("Socket closed");
            Object item;
            try {
                if (timeoutMs == 0) {
                    item = inbox.take();
                } else {
                    long left = deadline - System.nanoTime();
                    item = left <= 0 ? null : inbox.poll(left, TimeUnit.NANOSECONDS);
                    if (item == null) throw new SocketTimeoutException("Receive timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("receive interrompido");
            }
            if (item == CLOSED) {
                inbox.offer(CLOSED);
                throw new SocketException("Socket closed");
            }
            if (item instanceof Failure f) throw f.error();
            Incoming in = (Incoming) item;
            int n = Math.min(in.data().length, p.getData().length - p.getOffset());
            System.arraycopy(in.data(), 0, p.getData(), p.getOffset(), n);
            p.setLength(n);
            p.setAddress(in.address());
            p.setPort(in.port());
            return;
        }
    }

    private synchronized void startReader() {
        if (reader != null || closed) return;
        reader = daemon("impair-reader").newThread(this::readLoop);
        reader.start();
    }

    private void readLoop() {
        byte[] buf = new byte[MAX_DATAGRAM];
        while (!closed) {
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            try {
                delegate.receive(in);
            } catch (PortUnreachableException e) {
                inbox.offer(new Failure(e));
                continue;
            } catch (IOException e) {
                if (!closed) inbox.offer(new Failure(e));
                return;
            }
            Decision d = engine.decide(profile.get());
            if (d.drop()) continue;
            Incoming item = new Incoming(Arrays.copyOfRange(buf, 0, in.getLength()), in.getAddress(), in.getPort());
            if (d.delayMillis() <= 0) {
                inbox.offer(item);
            } else {
                try {
                    scheduler.schedule(() -> inbox.offer(item), d.delayMillis(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        delegate.close();
        scheduler.shutdownNow();
        inbox.offer(CLOSED);
    }
}
