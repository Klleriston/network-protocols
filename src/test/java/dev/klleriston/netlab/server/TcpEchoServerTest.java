package dev.klleriston.netlab.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.wire.MessageCodec;
import dev.klleriston.netlab.wire.TcpFraming;
import dev.klleriston.netlab.wire.TestMessage;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TcpEchoServerTest {

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private TcpEchoServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new TcpEchoServer(0, logs::add);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private Socket connect() throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port());
        s.setSoTimeout(3000);
        return s;
    }

    private boolean awaitLog(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (logs.stream().anyMatch(l -> l.contains(fragment))) return true;
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void echoesFramesUnchanged() throws Exception {
        try (Socket s = connect()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            for (int i = 0; i < 3; i++) {
                byte[] frame = MessageCodec.encode(TestMessage.of(i, 1000L + i, 64));
                TcpFraming.write(s.getOutputStream(), frame);
                assertArrayEquals(frame, TcpFraming.read(in));
            }
        }
    }

    @Test
    void logsGracefulClose() throws Exception {
        connect().close();
        assertTrue(awaitLog("FIN"), logs::toString);
    }

    @Test
    void logsResetClose() throws Exception {
        Socket s = connect();
        TcpFraming.write(s.getOutputStream(), MessageCodec.encode(TestMessage.of(1, 1, 8)));
        s.setSoLinger(true, 0);
        s.close();
        assertTrue(awaitLog("RST"), logs::toString);
    }

    @Test
    void closesConnectionOnOversizedFrame() throws Exception {
        try (Socket s = connect()) {
            s.getOutputStream().write(new byte[] {0, 1, 17, 112}); // 70000
            s.getOutputStream().flush();
            assertTrue(awaitLog("muito grande"), logs::toString);
            try {
                assertEquals(-1, s.getInputStream().read());
            } catch (java.net.SocketException expected) {
                // reset também é aceitável
            }
        }
    }

    @Test
    void oneBadConnectionDoesNotAffectOthers() throws Exception {
        try (Socket good = connect()) {
            Socket bad = connect();
            bad.setSoLinger(true, 0);
            bad.close();
            assertTrue(awaitLog("RST"), logs::toString);
            DataInputStream in = new DataInputStream(good.getInputStream());
            byte[] frame = MessageCodec.encode(TestMessage.of(7, 7, 32));
            TcpFraming.write(good.getOutputStream(), frame);
            assertArrayEquals(frame, TcpFraming.read(in));
        }
    }

    @Test
    void bindingUsedPortThrowsBindException() {
        TcpEchoServer second = new TcpEchoServer(server.port(), logs::add);
        assertThrows(BindException.class, second::start);
        second.close();
    }

    @Test
    void closeDisconnectsClients() throws Exception {
        try (Socket s = connect()) {
            assertTrue(awaitLog("aceita"), logs::toString);
            server.close();
            try {
                assertEquals(-1, s.getInputStream().read());
            } catch (java.net.SocketException expected) {
                // reset também é aceitável
            }
        }
    }
}
