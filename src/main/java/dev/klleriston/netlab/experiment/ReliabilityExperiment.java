package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.impair.ImpairmentProfile;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Envia {@code count} mensagens no ritmo {@code rate} por TCP e depois por UDP e compara entregas, perdas,
 * reordenações e chegadas tardias. O impair de rede afeta só o UDP.
 */
public final class ReliabilityExperiment implements Experiment {

    private static final int DEFAULT_COUNT = 1000;
    private static final int DEFAULT_RATE = 200;
    private static final int PAYLOAD = 64;
    private static final Duration LOSS_TIMEOUT = Duration.ofSeconds(1);

    private record Result(long delivered, long lost, long reordered) { }

    @Override
    public String name() {
        return "reliability";
    }

    @Override
    public String run(ExperimentContext ctx) throws Exception {
        int count = positive(ctx.intParam("count", DEFAULT_COUNT), "count");
        int rate = positive(ctx.intParam("rate", DEFAULT_RATE), "rate");
        long intervalNanos = 1_000_000_000L / rate;

        Result tcp = runTcp(ctx, count, intervalNanos);
        if (ctx.cancelled()) return "cancelado";

        try (UdpEchoSession udp = new UdpEchoSession(ctx, ctx.openUdp(), LOSS_TIMEOUT)) {
            udp.start();
            long start = System.nanoTime();
            for (int i = 0; i < count && !ctx.cancelled(); i++) {
                pace(start, i, intervalNanos);
                udp.send(i + 1L, PAYLOAD);
            }
            if (ctx.cancelled()) return "cancelado";
            udp.awaitDrained(drainWait(ctx.impairment()));
            return String.format(
                    "TCP: %d/%d entregues, %d perdidos, %d reordenados | UDP: %d/%d entregues, %d perdidos, "
                            + "%d reordenados, %d tardios",
                    tcp.delivered(), count, tcp.lost(), tcp.reordered(),
                    udp.echoed(), count, udp.lost(), udp.reordered(), udp.late());
        }
    }

    /** Uma thread envia no ritmo enquanto outra lê os echos contando inversões de {@code seq}. */
    private static Result runTcp(ExperimentContext ctx, int count, long intervalNanos) throws Exception {
        AtomicLong delivered = new AtomicLong();
        AtomicLong reordered = new AtomicLong();
        try (TcpEchoSession session = TcpEchoSession.connect(ctx, true)) {
            Thread reader = Thread.ofVirtual().name("reliability-tcp-reader").start(() -> {
                long highest = Long.MIN_VALUE;
                try {
                    for (int i = 0; i < count; i++) {
                        long seq = session.receive().seq();
                        delivered.incrementAndGet();
                        if (seq < highest) reordered.incrementAndGet();
                        else highest = seq;
                    }
                } catch (IOException e) {
                    // conexão quebrou (o evento já foi publicado) ou foi fechada pelo stop: o que faltou conta como perdido
                }
            });
            long start = System.nanoTime();
            try {
                for (int i = 0; i < count && !ctx.cancelled(); i++) {
                    pace(start, i, intervalNanos);
                    session.send(TestMessage.of(i + 1L, ctx.now(), PAYLOAD));
                }
            } catch (IOException e) {
                if (ctx.cancelled()) throw e;
                session.close(); // conexão quebrada: desbloqueia o leitor
            }
            if (ctx.cancelled()) session.close();
            reader.join();
        }
        return new Result(delivered.get(), count - delivered.get(), reordered.get());
    }

    /** Espera até o instante do envio {@code i} para manter o ritmo. */
    private static void pace(long start, int i, long intervalNanos) {
        long wait = start + i * intervalNanos - System.nanoTime();
        if (wait > 0) LockSupport.parkNanos(wait);
    }

    /** Timeout de perda + atraso + jitter + retenção de reordenação + 1 s de folga. */
    private static Duration drainWait(ImpairmentProfile p) {
        long reorderHoldMs = 20 + 2L * p.jitterMs();
        return LOSS_TIMEOUT.plusMillis(p.delayMs() + p.jitterMs() + reorderHoldMs).plusSeconds(1);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException("parâmetro " + name + " deve ser positivo: " + value);
        return value;
    }
}
