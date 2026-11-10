package dev.klleriston.netlab.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.wire.MessageCodec;
import dev.klleriston.netlab.wire.TestMessage;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UdpEchoServerTest {

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private UdpEchoServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new UdpEchoServer(0, logs::add);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private void send(DatagramSocket c, byte[] data) throws Exception {
        c.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), server.port()));
    }

    @Test
    void echoesDatagramUnchanged() throws Exception {
        try (DatagramSocket c = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            c.setSoTimeout(2000);
            byte[] data = MessageCodec.encode(TestMessage.of(5, 99, 64));
            send(c, data);
            DatagramPacket in = new DatagramPacket(new byte[70000], 70000);
            c.receive(in);
            assertArrayEquals(data, java.util.Arrays.copyOf(in.getData(), in.getLength()));
            assertEquals(0, server.malformedCount());
        }
    }

    @Test
    void ignoresMalformedDatagram() throws Exception {
        try (DatagramSocket c = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            c.setSoTimeout(300);
            send(c, new byte[5]);
            assertThrows(SocketTimeoutException.class, () -> c.receive(new DatagramPacket(new byte[100], 100)));
            assertEquals(1, server.malformedCount());
        }
    }
}
