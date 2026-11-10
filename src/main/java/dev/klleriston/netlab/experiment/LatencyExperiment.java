package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.metrics.RttStats;
import java.time.Duration;

/** Mede o RTT de echo sequencial por TCP (com Nagle ligado ou desligado) e por UDP e resume média e p95. */
public final class LatencyExperiment implements Experiment {

    private static final int DEFAULT_SAMPLES = 200;
    private static final int DEFAULT_SIZE = 64;
    private static final Duration LOSS_TIMEOUT = Duration.ofSeconds(1);

    @Override
    public String name() {
        return "latency";
    }

    @Override
    public String run(ExperimentContext ctx) throws Exception {
        int samples = positive(ctx.intParam("samples", DEFAULT_SAMPLES), "samples");
        int size = positive(ctx.intParam("size", DEFAULT_SIZE), "size");
        String nagle = ctx.param("nagle", "off");
        if (!nagle.equals("on") && !nagle.equals("off")) {
            throw new IllegalArgumentException("nagle deve ser on ou off");
        }

        RttStats tcpStats = new RttStats();
        try (TcpEchoSession tcp = TcpEchoSession.connect(ctx, nagle.equals("off"))) {
            for (int i = 0; i < samples && !ctx.cancelled(); i++) {
                tcpStats.add(tcp.roundTrip(i + 1L, size));
            }
            if (ctx.cancelled()) return "cancelado";
            tcp.closeGraceful();
        }

        RttStats udpStats = new RttStats();
        long lost;
        try (UdpEchoSession udp = new UdpEchoSession(ctx, ctx.openUdp(), LOSS_TIMEOUT)) {
            udp.onEchoRtt(udpStats::add);
            udp.start();
            for (int i = 0; i < samples && !ctx.cancelled(); i++) {
                udp.send(i + 1L, size);
                udp.awaitDrained(LOSS_TIMEOUT.plusSeconds(1)); // ping-pong: espera echo ou perda
            }
            if (ctx.cancelled()) return "cancelado";
            lost = udp.lost();
        }

        return String.format("TCP: avg %d µs, p95 %d µs | UDP: avg %d µs, p95 %d µs, perdidos %d | nagle=%s",
                tcpStats.avgMicros(), tcpStats.p95Micros(), udpStats.avgMicros(), udpStats.p95Micros(), lost, nagle);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException("parâmetro " + name + " deve ser positivo: " + value);
        return value;
    }
}
