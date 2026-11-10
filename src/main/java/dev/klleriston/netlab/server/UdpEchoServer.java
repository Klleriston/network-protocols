package dev.klleriston.netlab.server;

import dev.klleriston.netlab.wire.MalformedMessageException;
import dev.klleriston.netlab.wire.MessageCodec;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Servidor de echo UDP: valida o datagrama e o devolve inalterado; malformados são ignorados. */
public final class UdpEchoServer implements AutoCloseable {

    private final int requestedPort;
    private final Consumer<String> log;
    private final AtomicLong malformed = new AtomicLong();
    private volatile DatagramSocket socket;
    private volatile boolean closed;

    public UdpEchoServer(int port, Consumer<String> log) {
        this.requestedPort = port;
        this.log = log;
    }

    public void start() throws IOException {
        DatagramSocket s = new DatagramSocket(requestedPort);
        s.setReceiveBufferSize(4 * 1024 * 1024);
        socket = s;
        Thread.ofVirtual().name("udp-echo").start(() -> loop(s));
    }

    public int port() {
        return socket.getLocalPort();
    }

    public long malformedCount() {
        return malformed.get();
    }

    private void loop(DatagramSocket s) {
        byte[] buf = new byte[TestMessage.MAX_BYTES + 1024];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                packet.setLength(buf.length);
                s.receive(packet);
                try {
                    MessageCodec.decode(buf, 0, packet.getLength());
                } catch (MalformedMessageException e) {
                    malformed.incrementAndGet();
                    log.accept("datagrama malformado de " + packet.getSocketAddress() + " ignorado");
                    continue;
                }
                s.send(new DatagramPacket(buf, packet.getLength(), packet.getSocketAddress()));
            } catch (IOException e) {
                if (closed) return;
                log.accept("erro UDP: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        DatagramSocket s = socket;
        if (s != null) s.close();
    }
}
