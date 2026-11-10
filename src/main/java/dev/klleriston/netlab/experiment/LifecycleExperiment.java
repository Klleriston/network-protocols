package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.impair.ImpairedDatagramSocket;
import dev.klleriston.netlab.wire.MessageCodec;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Roteiro do ciclo de vida: FIN, RST, porta TCP recusada, UDP sem handshake e UDP para porta fechada. */
public final class LifecycleExperiment implements Experiment {

    private static final int DEFAULT_CLOSED_PORT = 7999;
    private static final int PAYLOAD = 64;
    private static final int UDP_TIMEOUT_MS = 1000;

    @Override
    public String name() {
        return "lifecycle";
    }

    @Override
    public String run(ExperimentContext ctx) throws Exception {
        int closedPort = ctx.intParam("closed-port", DEFAULT_CLOSED_PORT);
        String closedRemote = ctx.target().host().getHostAddress() + ":" + closedPort;

        // 1. handshake, uma ida e volta e fechamento com FIN
        long handshakeMicros;
        try (TcpEchoSession s = TcpEchoSession.connect(ctx, true)) {
            handshakeMicros = s.handshakeMicros();
            s.roundTrip(1, PAYLOAD);
            s.closeGraceful();
        }

        // 2. handshake e fechamento abortivo com RST
        try (TcpEchoSession s = TcpEchoSession.connect(ctx, true)) {
            s.closeAbortive();
        }

        // 3. conexão a uma porta fechada: a recusa é o resultado esperado, não falha
        tcpToClosedPort(ctx, closedPort, closedRemote);

        // 4. UDP ao servidor: sem handshake, só o datagrama e o echo
        udpToServer(ctx);

        // 5. UDP para porta fechada: silêncio ou ICMP de porta inalcançável
        udpToClosedPort(ctx, closedPort, closedRemote);

        return "handshake TCP: " + handshakeMicros + " µs";
    }

    private static void tcpToClosedPort(ExperimentContext ctx, int port, String remote) throws IOException {
        try (Socket s = ctx.track(new Socket())) {
            s.connect(new InetSocketAddress(ctx.target().host(), port), TcpEchoSession.CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            if (ctx.cancelled()) throw e;
            ctx.bus().publish(NetErrors.toEvent(e, Protocol.TCP, remote, ctx.now()).orElseThrow(() -> e));
        }
    }

    private static void udpToServer(ExperimentContext ctx) throws IOException {
        try (ImpairedDatagramSocket s = ctx.openUdp()) {
            s.setSoTimeout(UDP_TIMEOUT_MS);
            TestMessage m = TestMessage.of(1, ctx.now(), PAYLOAD);
            byte[] data = MessageCodec.encode(m);
            s.send(new DatagramPacket(data, data.length));
            ctx.bus().publish(new Events.PacketSent(ctx.now(), Protocol.UDP, m.seq(), data.length));
            byte[] buf = new byte[TestMessage.MAX_BYTES];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            try {
                s.receive(in);
            } catch (SocketTimeoutException e) {
                ctx.bus().publish(new Events.PacketLost(ctx.now(), Protocol.UDP, m.seq()));
                return;
            }
            TestMessage echo = MessageCodec.decode(buf, 0, in.getLength());
            long now = ctx.now();
            long rttNanos = now - echo.sentAtNanos();
            ctx.bus().publish(new Events.PacketEchoed(now, Protocol.UDP, echo.seq(), rttNanos, in.getLength()));
            ctx.bus().publish(new Events.UdpExchange(now, ctx.target().udpRemote(), rttNanos / 1_000));
        }
    }

    private static void udpToClosedPort(ExperimentContext ctx, int port, String remote) throws IOException {
        try (DatagramSocket s = ctx.track(new DatagramSocket())) {
            s.connect(ctx.target().host(), port);
            s.setSoTimeout(UDP_TIMEOUT_MS);
            TestMessage m = TestMessage.of(2, ctx.now(), PAYLOAD);
            byte[] data = MessageCodec.encode(m);
            s.send(new DatagramPacket(data, data.length));
            ctx.bus().publish(new Events.PacketSent(ctx.now(), Protocol.UDP, m.seq(), data.length));
            try {
                s.receive(new DatagramPacket(new byte[TestMessage.MAX_BYTES], TestMessage.MAX_BYTES));
            } catch (PortUnreachableException e) {
                ctx.bus().publish(new Events.UdpPortUnreachable(ctx.now(), remote));
            } catch (SocketTimeoutException e) {
                ctx.bus().publish(new Events.PacketLost(ctx.now(), Protocol.UDP, m.seq()));
            }
        }
    }
}
