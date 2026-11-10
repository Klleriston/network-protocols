package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.impair.ImpairmentProfile;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Mede vazão: TCP em rajada (sem ritmo) por {@code seconds}, depois UDP na taxa {@code udp-rate} pelo mesmo tempo,
 * comparando o que foi ecoado com o que foi enviado.
 */
public final class ThroughputExperiment implements Experiment {

    private static final int DEFAULT_SECONDS = 10;
    private static final String DEFAULT_UDP_RATE = "20mbps";
    private static final int DEFAULT_SIZE = 1200;
    private static final int MAX_SIZE = 65_000;
    private static final Duration LOSS_TIMEOUT = Duration.ofSeconds(1);
    private static final long TCP_DRAIN_NANOS = 5_000_000_000L;
    private static final long BATCH_INTERVAL_NANOS = 1_000_000L;
    private static final long POLL_NANOS = 5_000_000L;

    @Override
    public String name() {
        return "throughput";
    }

    @Override
    public String run(ExperimentContext ctx) throws Exception {
        int seconds = ctx.intParam("seconds", DEFAULT_SECONDS);
        if (seconds <= 0) throw new IllegalArgumentException("parâmetro seconds deve ser positivo: " + seconds);
        int size = ctx.intParam("size", DEFAULT_SIZE);
        if (size <= 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("parâmetro size deve estar entre 1 e " + MAX_SIZE + ": " + size);
        }
        long rate = Rates.parseBitsPerSecond(ctx.param("udp-rate", DEFAULT_UDP_RATE));
        long phaseNanos = seconds * 1_000_000_000L;

        double tcpMbps = runTcp(ctx, phaseNanos, size);
        if (ctx.cancelled()) return "cancelado";

        double[] udp = runUdp(ctx, phaseNanos, size, rate);
        if (ctx.cancelled()) return "cancelado";
        return String.format(Locale.ROOT,
                "TCP: %.1f Mbit/s | UDP: alvo %.1f Mbit/s, entregue %.1f Mbit/s, perda %.2f%%",
                tcpMbps, rate / 1e6, udp[0], udp[1]);
    }

    /** Escreve sem ritmo até o prazo enquanto outra thread conta os echos; devolve a vazão ecoada em Mbit/s. */
    private static double runTcp(ExperimentContext ctx, long phaseNanos, int size) throws Exception {
        AtomicLong echoed = new AtomicLong();
        AtomicBoolean readerDead = new AtomicBoolean();
        try (TcpEchoSession session = TcpEchoSession.connect(ctx, true)) {
            Thread reader = Thread.ofVirtual().name("throughput-tcp-reader").start(() -> {
                try {
                    while (true) {
                        session.receive();
                        echoed.incrementAndGet();
                    }
                } catch (IOException e) {
                    // fechada pelo fim da fase, pelo stop, ou quebrada (evento já publicado)
                } finally {
                    readerDead.set(true);
                }
            });
            long start = System.nanoTime();
            long deadline = start + phaseNanos;
            long sent = 0;
            try {
                while (!ctx.cancelled() && !readerDead.get() && System.nanoTime() < deadline) {
                    session.send(TestMessage.of(sent + 1, ctx.now(), size));
                    sent++;
                }
            } catch (IOException e) {
                if (!ctx.cancelled()) {
                    session.close();
                    reader.join();
                    throw e; // conexão quebrada fora de um stop: o experimento falha
                }
            }
            long drainDeadline = System.nanoTime() + TCP_DRAIN_NANOS;
            while (!ctx.cancelled() && echoed.get() < sent && reader.isAlive() && System.nanoTime() < drainDeadline) {
                LockSupport.parkNanos(POLL_NANOS);
            }
            long end = System.nanoTime();
            session.close(); // desbloqueia o leitor
            reader.join();
            return mbps(echoed.get(), size, Math.max(end - start, 1));
        }
    }

    /** Envia em lotes de ~1 ms na taxa alvo; devolve {entregue em Mbit/s, perda em %}. */
    private static double[] runUdp(ExperimentContext ctx, long phaseNanos, int size, long rate) throws Exception {
        try (UdpEchoSession udp = new UdpEchoSession(ctx, ctx.openUdp(), LOSS_TIMEOUT)) {
            udp.start();
            // a taxa alvo conta o datagrama inteiro (cabeçalho + payload), como a vazão entregue
            double nanosPerPacket = frameBytes(size) * 8.0 * 1e9 / rate;
            long sent = 0;
            long start = System.nanoTime();
            boolean last = false;
            while (!ctx.cancelled() && !last) {
                long elapsed = System.nanoTime() - start;
                if (elapsed >= phaseNanos) {
                    elapsed = phaseNanos; // último lote: fecha a conta no prazo
                    last = true;
                }
                long due = (long) (elapsed / nanosPerPacket) - sent;
                for (long i = 0; i < due && !ctx.cancelled(); i++) {
                    try {
                        udp.send(++sent, size);
                    } catch (IOException e) {
                        // ex.: sem espaço de buffer no SO: segue pendente e a varredura o declara perdido
                    }
                }
                if (!last) LockSupport.parkNanos(BATCH_INTERVAL_NANOS);
            }
            long sendNanos = Math.max(System.nanoTime() - start, 1);
            if (ctx.cancelled()) return new double[] {0, 0};
            udp.awaitDrained(drainWait(ctx.impairment()));
            double delivered = mbps(udp.echoed(), size, sendNanos);
            double loss = sent == 0 ? 0 : udp.lost() * 100.0 / sent;
            return new double[] {delivered, loss};
        }
    }

    /**
     * Vazão em Mbit/s de {@code frames} mensagens com {@code payloadSize} bytes de payload em {@code nanos};
     * conta os bytes codificados (cabeçalho + payload), os mesmos do {@code PacketEchoed} que o painel soma.
     */
    static double mbps(long frames, int payloadSize, long nanos) {
        return frames * (double) frameBytes(payloadSize) * 8 / (nanos / 1e9) / 1e6;
    }

    private static int frameBytes(int payloadSize) {
        return payloadSize + TestMessage.HEADER_BYTES;
    }

    /** Timeout de perda + atraso + jitter + retenção de reordenação + 1 s de folga. */
    private static Duration drainWait(ImpairmentProfile p) {
        long reorderHoldMs = 20 + 2L * p.jitterMs();
        return LOSS_TIMEOUT.plusMillis(p.delayMs() + p.jitterMs() + reorderHoldMs).plusSeconds(1);
    }
}
