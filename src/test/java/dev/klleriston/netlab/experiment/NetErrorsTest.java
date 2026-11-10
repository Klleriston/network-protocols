package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetErrorsTest {

    private static final String REMOTE = "127.0.0.1:7999";

    @Test
    void mapsConnectExceptionToRefused() {
        Optional<Event> e = NetErrors.toEvent(new ConnectException("Connection refused"), Protocol.TCP, REMOTE, 10);
        assertEquals(Optional.of(new Events.ConnectionRefused(10, Protocol.TCP, REMOTE)), e);
    }

    @Test
    void mapsSocketTimeoutToConnectTimeout() {
        Optional<Event> e = NetErrors.toEvent(new SocketTimeoutException("Connect timed out"), Protocol.TCP, REMOTE, 11);
        assertEquals(Optional.of(new Events.ConnectTimeout(11, Protocol.TCP, REMOTE)), e);
    }

    @Test
    void mapsConnectionResetToReset() {
        assertEquals(Optional.of(new Events.ConnectionReset(12, Protocol.TCP, REMOTE)),
                NetErrors.toEvent(new SocketException("Connection reset"), Protocol.TCP, REMOTE, 12));
        assertEquals(Optional.of(new Events.ConnectionReset(13, Protocol.TCP, REMOTE)),
                NetErrors.toEvent(new SocketException("Connection reset by peer"), Protocol.TCP, REMOTE, 13));
        assertEquals(Optional.of(new Events.ConnectionReset(14, Protocol.TCP, REMOTE)),
                NetErrors.toEvent(new SocketException("Broken pipe"), Protocol.TCP, REMOTE, 14));
    }

    @Test
    void mapsPortUnreachableToUdpPortUnreachable() {
        Optional<Event> e = NetErrors.toEvent(new PortUnreachableException("ICMP"), Protocol.UDP, REMOTE, 15);
        assertEquals(Optional.of(new Events.UdpPortUnreachable(15, REMOTE)), e);
    }

    @Test
    void mapsEofToGracefulClose() {
        assertEquals(Optional.of(new Events.ConnectionClosed(16, Protocol.TCP, REMOTE, true)),
                NetErrors.toEvent(new EOFException(), Protocol.TCP, REMOTE, 16));
    }

    @Test
    void unknownErrorsMapToNothing() {
        assertEquals(Optional.empty(), NetErrors.toEvent(new SocketException("Socket closed"), Protocol.TCP, REMOTE, 1));
        assertEquals(Optional.empty(), NetErrors.toEvent(new IOException("x"), Protocol.TCP, REMOTE, 1));
        assertEquals(Optional.empty(), NetErrors.toEvent(new IllegalStateException(), Protocol.TCP, REMOTE, 1));
    }

    @Test
    void unreachableClassification() {
        assertTrue(NetErrors.isUnreachable(new ConnectException()));
        assertTrue(NetErrors.isUnreachable(new SocketTimeoutException()));
        assertTrue(NetErrors.isUnreachable(new NoRouteToHostException()));
        assertTrue(NetErrors.isUnreachable(new UnknownHostException()));
        assertFalse(NetErrors.isUnreachable(new SocketException("Connection reset")));
        assertFalse(NetErrors.isUnreachable(new IllegalStateException()));
    }
}
