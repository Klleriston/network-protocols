package dev.klleriston.netlab.impair;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImpairedDatagramSocketTest {
    private DatagramSocket peer;
    private DatagramSocket local;
    private final AtomicReference<ImpairmentProfile> profile = new AtomicReference<>(ImpairmentProfile.NONE);
    private ImpairedDatagramSocket impaired;

    @BeforeEach void setUp() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        peer = new DatagramSocket(0, lo);
        local = new DatagramSocket(0, lo);
        local.connect(lo, peer.getLocalPort());
        impaired = new ImpairedDatagramSocket(local, profile::get, new ImpairmentEngine(new Random(42)));
    }

    @AfterEach void tearDown() {
        impaired.close();
        peer.close();
    }

    private static DatagramPacket packet(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new DatagramPacket(b, b.length);
    }

    private String receiveAtPeer(int timeoutMs) throws Exception {
        peer.setSoTimeout(timeoutMs);
        DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
        peer.receive(p);
        return new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
    }

    @Test void passThroughWhenProfileIsNone() throws Exception {
        impaired.send(packet("ola"));
        assertEquals("ola", receiveAtPeer(2000));

        byte[] b = "volta".getBytes(StandardCharsets.UTF_8);
        peer.send(new DatagramPacket(b, b.length, InetAddress.getByName("127.0.0.1"), local.getLocalPort()));
        impaired.setSoTimeout(2000);
        DatagramPacket in = new DatagramPacket(new byte[1024], 1024);
        impaired.receive(in);
        assertEquals("volta", new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8));
        assertEquals(peer.getLocalPort(), in.getPort());
        assertNotNull(in.getAddress());
    }

    @Test void dropsEverythingAtFullLoss() throws Exception {
        profile.set(new ImpairmentProfile(100, 0, 0, 0));
        for (int i = 0; i < 20; i++) impaired.send(packet("x" + i));
        assertThrows(SocketTimeoutException.class, () -> receiveAtPeer(300));
    }

    @Test void delaysDelivery() throws Exception {
        profile.set(new ImpairmentProfile(0, 100, 0, 0));
        long t0 = System.nanoTime();
        impaired.send(packet("lento"));
        assertEquals("lento", receiveAtPeer(3000));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 90, "ms=" + ms);
    }

    @Test void receiveTimesOut() throws Exception {
        impaired.setSoTimeout(100);
        long t0 = System.nanoTime();
        assertThrows(SocketTimeoutException.class, () -> impaired.receive(new DatagramPacket(new byte[16], 16)));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 90, "ms=" + ms);
    }

    @Test void profileChangeAppliesToNextPacket() throws Exception {
        profile.set(new ImpairmentProfile(100, 0, 0, 0));
        impaired.send(packet("perdido"));
        profile.set(ImpairmentProfile.NONE);
        impaired.send(packet("chega"));
        assertEquals("chega", receiveAtPeer(2000));
    }

    @Test void closeUnblocksPendingReceive() throws Exception {
        impaired.setSoTimeout(0);
        var result = new AtomicReference<Throwable>();
        Thread t = new Thread(() -> {
            try {
                impaired.receive(new DatagramPacket(new byte[16], 16));
            } catch (Throwable e) {
                result.set(e);
            }
        });
        t.start();
        Thread.sleep(200);
        impaired.close();
        t.join(3000);
        assertFalse(t.isAlive());
        assertInstanceOf(SocketException.class, result.get());
    }

    @Test void receiveTruncatesToBufferLength() throws Exception {
        byte[] b = "abcdefgh".getBytes(StandardCharsets.UTF_8);
        peer.send(new DatagramPacket(b, b.length, InetAddress.getByName("127.0.0.1"), local.getLocalPort()));
        impaired.setSoTimeout(2000);
        DatagramPacket in = new DatagramPacket(new byte[4], 4);
        impaired.receive(in);
        assertEquals(4, in.getLength());
        assertEquals("abcd", new String(in.getData(), 0, 4, StandardCharsets.UTF_8));
    }

    private ImpairedDatagramSocket toClosedPort() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        int closedPort;
        try (DatagramSocket tmp = new DatagramSocket(0, lo)) {
            closedPort = tmp.getLocalPort();
        }
        DatagramSocket s = new DatagramSocket(0, lo);
        s.connect(lo, closedPort);
        return new ImpairedDatagramSocket(s, profile::get, new ImpairmentEngine(new Random(42)));
    }

    private void assertUnreachableThenTimeout(ImpairedDatagramSocket sock) throws Exception {
        sock.setSoTimeout(2000);
        DatagramPacket in = new DatagramPacket(new byte[16], 16);
        try {
            sock.receive(in);
            fail("esperava PortUnreachableException");
        } catch (SocketTimeoutException e) {
            assumeTrue(false, "ICMP port unreachable nao entregue nesta plataforma");
        } catch (PortUnreachableException expected) {
            // ok
        }
        sock.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> sock.receive(in));
    }

    @Test void portUnreachableIsPassedThroughAndReceiveWorksAfter() throws Exception {
        try (ImpairedDatagramSocket sock = toClosedPort()) {
            sock.send(packet("x"));
            assertUnreachableThenTimeout(sock);
        }
    }

    @Test void portUnreachableFromDelayedSendReachesReceive() throws Exception {
        profile.set(new ImpairmentProfile(0, 50, 0, 0));
        try (ImpairedDatagramSocket sock = toClosedPort()) {
            sock.setSoTimeout(1);
            try { sock.receive(new DatagramPacket(new byte[16], 16)); } catch (SocketTimeoutException ignored) { }
            sock.send(packet("x"));
            assertUnreachableThenTimeout(sock);
        }
    }
}
