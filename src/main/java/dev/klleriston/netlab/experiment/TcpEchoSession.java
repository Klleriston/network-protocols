package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import dev.klleriston.netlab.wire.MessageCodec;
import dev.klleriston.netlab.wire.TcpFraming;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/** Conexão TCP com o servidor de echo que publica cada passo do seu ciclo de vida. */
public final class TcpEchoSession implements AutoCloseable {

    static final int CONNECT_TIMEOUT_MS = 3000;
    static final int READ_TIMEOUT_MS = 5000;

    private final ExperimentContext ctx;
    private final Socket socket;
    private final DataInputStream in;
    private final String remote;
    private final long handshakeMicros;
    private boolean closed;

    private TcpEchoSession(ExperimentContext ctx, Socket socket, String remote, long handshakeMicros)
            throws IOException {
        this.ctx = ctx;
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.remote = remote;
        this.handshakeMicros = handshakeMicros;
    }

    /** Conecta ao alvo (timeout 3 s, leitura 5 s) e publica {@code ConnectionEstablished}. */
    public static TcpEchoSession connect(ExperimentContext ctx, boolean noDelay) throws IOException {
        Target target = ctx.target();
        Socket socket = ctx.track(new Socket());
        try {
            socket.setTcpNoDelay(noDelay);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            long t0 = ctx.now();
            socket.connect(new InetSocketAddress(target.host(), target.tcpPort()), CONNECT_TIMEOUT_MS);
            long t1 = ctx.now();
            long handshake = (t1 - t0) / 1000;
            TcpEchoSession session = new TcpEchoSession(ctx, socket, target.tcpRemote(), handshake);
            ctx.bus().publish(new Events.ConnectionEstablished(t1, Protocol.TCP, target.tcpRemote(), handshake));
            return session;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    public long handshakeMicros() {
        return handshakeMicros;
    }

    /** Estado atual de TCP_NODELAY no socket; false se não for possível ler. */
    public boolean tcpNoDelay() {
        try {
            return socket.getTcpNoDelay();
        } catch (SocketException e) {
            return false;
        }
    }

    /** Envia a mensagem enquadrada e publica {@code PacketSent}. */
    public void send(TestMessage m) throws IOException {
        byte[] frame = MessageCodec.encode(m);
        try {
            TcpFraming.write(socket.getOutputStream(), frame);
        } catch (SocketException e) {
            publishResetIfPeerAbort(e);
            throw e;
        }
        ctx.bus().publish(new Events.PacketSent(ctx.now(), Protocol.TCP, m.seq(), frame.length));
    }

    /** Lê o próximo echo e publica {@code PacketEchoed}; FIN e RST do par viram eventos e são relançados. */
    public TestMessage receive() throws IOException {
        byte[] frame;
        try {
            frame = TcpFraming.read(in);
        } catch (EOFException e) {
            markClosed();
            ctx.bus().publish(new Events.ConnectionClosed(ctx.now(), Protocol.TCP, remote, true));
            throw e;
        } catch (SocketException e) {
            publishResetIfPeerAbort(e);
            throw e;
        }
        TestMessage m = MessageCodec.decode(frame, 0, frame.length);
        long now = ctx.now();
        ctx.bus().publish(new Events.PacketEchoed(now, Protocol.TCP, m.seq(), now - m.sentAtNanos(), frame.length));
        return m;
    }

    /** Envia uma mensagem, espera o echo e devolve o RTT em nanossegundos. */
    public long roundTrip(long seq, int payloadSize) throws IOException {
        send(TestMessage.of(seq, ctx.now(), payloadSize));
        TestMessage echo = receive();
        return ctx.now() - echo.sentAtNanos();
    }

    /** Fecha com FIN e publica {@code ConnectionClosed(graceful=true)}. */
    public void closeGraceful() throws IOException {
        if (markClosed()) return;
        socket.close();
        ctx.bus().publish(new Events.ConnectionClosed(ctx.now(), Protocol.TCP, remote, true));
    }

    /** Fecha com RST ({@code SO_LINGER=0}) e publica {@code ConnectionClosed(graceful=false)}. */
    public void closeAbortive() throws IOException {
        if (markClosed()) return;
        try {
            socket.setSoLinger(true, 0);
        } finally {
            socket.close();
        }
        ctx.bus().publish(new Events.ConnectionClosed(ctx.now(), Protocol.TCP, remote, false));
    }

    /** Fecha sem publicar evento; idempotente. */
    @Override
    public void close() throws IOException {
        markClosed();
        socket.close();
    }

    /** Marca como fechado; true se já estava. */
    private synchronized boolean markClosed() {
        boolean was = closed;
        closed = true;
        return was;
    }

    private void publishResetIfPeerAbort(SocketException e) {
        if (NetErrors.isPeerReset(e)) {
            markClosed();
            ctx.bus().publish(new Events.ConnectionReset(ctx.now(), Protocol.TCP, remote));
        }
    }
}
