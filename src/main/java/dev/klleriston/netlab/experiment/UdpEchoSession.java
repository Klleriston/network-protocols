package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.impair.ImpairedDatagramSocket;
import dev.klleriston.netlab.wire.MalformedMessageException;
import dev.klleriston.netlab.wire.MessageCodec;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.PortUnreachableException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Sessão UDP com o servidor de echo: envia mensagens numeradas e classifica cada echo como ecoado, reordenado ou
 * tardio. Um pacote sem echo dentro de {@code lossTimeout} é declarado perdido; se o echo chegar depois, conta só
 * como tardio — nunca como ecoado nem como uma segunda perda.
 */
public final class UdpEchoSession implements AutoCloseable {

    private static final long SWEEP_INTERVAL_MS = 50;
    private static final long DRAIN_POLL_MS = 10;

    private final ExperimentContext ctx;
    private final ImpairedDatagramSocket socket;
    private final long lossTimeoutNanos;
    private final String remote;

    /** seq → instante de envio (nanos) dos pacotes ainda sem echo nem perda declarada. */
    private final Map<Long, Long> pending = new ConcurrentHashMap<>();
    /** Transições de estado (pendente → ecoado/perdido/tardio) acontecem sob este lock; a remoção de
     *  {@code pending} é sempre o último passo, depois dos contadores. */
    private final Object lock = new Object();
    private final Set<Long> lostSeqs = new HashSet<>();
    private final Set<Long> lateSeqs = new HashSet<>();
    private long highestSeen = Long.MIN_VALUE;

    private final AtomicLong echoed = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final AtomicLong reordered = new AtomicLong();
    private final AtomicLong late = new AtomicLong();
    private final AtomicBoolean portUnreachableReported = new AtomicBoolean();
    private volatile LongConsumer echoListener = rtt -> { };
    private volatile boolean closed;
    private Thread receiver;
    private Thread sweeper;

    public UdpEchoSession(ExperimentContext ctx, ImpairedDatagramSocket socket, Duration lossTimeout) {
        this.ctx = ctx;
        this.socket = socket;
        this.lossTimeoutNanos = lossTimeout.toNanos();
        this.remote = ctx.target().udpRemote();
    }

    /** Inicia as threads de recepção e de varredura de perdas e registra a sessão no contexto. */
    public synchronized void start() {
        if (receiver != null) return;
        ctx.track(this);
        receiver = Thread.ofVirtual().name("udp-session-receiver").start(this::receiveLoop);
        sweeper = Thread.ofVirtual().name("udp-session-sweeper").start(this::sweepLoop);
    }

    /** Envia a mensagem {@code seq} com payload zerado e publica {@code PacketSent}. */
    public void send(long seq, int payloadSize) throws IOException {
        long now = ctx.now();
        byte[] data = MessageCodec.encode(TestMessage.of(seq, now, payloadSize));
        pending.put(seq, now);
        ctx.bus().publish(new Events.PacketSent(now, Protocol.UDP, seq, data.length));
        try {
            socket.send(new DatagramPacket(data, data.length));
        } catch (PortUnreachableException e) {
            // ICMP de um envio anterior: o pacote fica pendente e será declarado perdido
            reportPortUnreachable();
        } catch (IOException e) {
            // falha do SO ao enviar: o pacote segue pendente e será declarado perdido pela varredura
            throw e;
        }
    }

    /** Espera até não haver pacotes pendentes; true se drenou dentro do prazo. */
    public boolean awaitDrained(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (!pending.isEmpty()) {
            if (closed || System.nanoTime() >= deadline) return pending.isEmpty();
            try {
                Thread.sleep(DRAIN_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending.isEmpty();
            }
        }
        return true;
    }

    /** Recebe o RTT (nanos) de cada echo classificado como ecoado, antes de ele deixar de estar pendente. */
    public void onEchoRtt(LongConsumer listener) {
        this.echoListener = listener;
    }

    public long echoed() {
        return echoed.get();
    }

    public long lost() {
        return lost.get();
    }

    public long reordered() {
        return reordered.get();
    }

    public long late() {
        return late.get();
    }

    /** Para as threads e fecha o socket; idempotente. */
    @Override
    public void close() {
        Thread r;
        Thread s;
        synchronized (this) {
            if (closed) return;
            closed = true;
            r = receiver;
            s = sweeper;
        }
        socket.close();
        if (s != null) s.interrupt();
        if (r != null) r.interrupt();
    }

    private void receiveLoop() {
        byte[] buf = new byte[TestMessage.MAX_BYTES];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        while (!closed && !ctx.cancelled()) {
            in.setLength(buf.length);
            try {
                socket.receive(in);
            } catch (PortUnreachableException e) {
                reportPortUnreachable();
                continue;
            } catch (IOException e) {
                return; // socket fechado (close/stop) ou quebrado: a recepção termina
            }
            TestMessage m;
            try {
                m = MessageCodec.decode(buf, 0, in.getLength());
            } catch (MalformedMessageException e) {
                continue;
            }
            onEcho(m.seq(), in.getLength());
        }
    }

    private void onEcho(long seq, int bytes) {
        synchronized (lock) {
            Long sentAt = pending.get(seq);
            long now = ctx.now();
            if (sentAt == null) {
                if (lostSeqs.contains(seq) && lateSeqs.add(seq)) {
                    late.incrementAndGet();
                    ctx.bus().publish(new Events.PacketLate(now, Protocol.UDP, seq));
                }
                return; // seq nunca enviado ou echo duplicado: ignorado
            }
            echoed.incrementAndGet();
            echoListener.accept(now - sentAt);
            ctx.bus().publish(new Events.PacketEchoed(now, Protocol.UDP, seq, now - sentAt, bytes));
            if (seq < highestSeen) {
                reordered.incrementAndGet();
                ctx.bus().publish(new Events.PacketReordered(now, Protocol.UDP, seq, highestSeen));
            } else {
                highestSeen = seq;
            }
            pending.remove(seq); // por último: quem vê o pendente sumir já vê os contadores atualizados
        }
    }

    private void sweepLoop() {
        while (!closed && !ctx.cancelled()) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            sweep();
        }
    }

    private void sweep() {
        long now = ctx.now();
        for (Map.Entry<Long, Long> e : pending.entrySet()) {
            if (now - e.getValue() < lossTimeoutNanos) continue;
            long seq = e.getKey();
            synchronized (lock) {
                if (!e.getValue().equals(pending.get(seq))) continue; // ecoado nesse meio-tempo
                lostSeqs.add(seq);
                lost.incrementAndGet();
                ctx.bus().publish(new Events.PacketLost(ctx.now(), Protocol.UDP, seq));
                pending.remove(seq);
            }
        }
    }

    private void reportPortUnreachable() {
        if (portUnreachableReported.compareAndSet(false, true)) {
            ctx.bus().publish(new Events.UdpPortUnreachable(ctx.now(), remote));
        }
    }
}
