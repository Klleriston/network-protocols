package dev.klleriston.netlab.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class ArgsTest {

    @Test
    void serverDefaults() {
        assertEquals(new Args.ServerArgs(7000, 7001), Args.parse(new String[] {"server"}));
    }

    @Test
    void serverParsesPorts() {
        assertEquals(new Args.ServerArgs(9000, 9001),
                Args.parse(new String[] {"server", "--tcp-port", "9000", "--udp-port", "9001"}));
    }

    @Test
    void clientRequiresTarget() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"client"}));
        assertTrue(e.getMessage().contains("--target"), e.getMessage());
    }

    @Test
    void clientParsesAllFlags() throws Exception {
        Args args = Args.parse(new String[] {"client", "--target", "127.0.0.1", "--http-port", "9090"});
        Args.ClientArgs client = assertInstanceOf(Args.ClientArgs.class, args);
        assertEquals(InetAddress.getByName("127.0.0.1"), client.target());
        assertEquals(7000, client.tcpPort());
        assertEquals(7001, client.udpPort());
        assertEquals(9090, client.httpPort());

        Args.ClientArgs all = (Args.ClientArgs) Args.parse(new String[] {
                "client", "--tcp-port", "1", "--udp-port", "2", "--http-port", "3", "--target", "localhost"});
        assertEquals(1, all.tcpPort());
        assertEquals(2, all.udpPort());
        assertEquals(3, all.httpPort());
        assertTrue(all.target().isLoopbackAddress());
    }

    @Test
    void unknownModeShowsUsage() {
        for (String[] argv : new String[][] {{}, {"banana"}}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Args.parse(argv));
            assertTrue(e.getMessage().contains(Args.USAGE), e.getMessage());
        }
    }

    @Test
    void rejectsBadFlagsAndValues() {
        String[][] bad = {
                {"server", "--http-port", "8080"},
                {"server", "--tcp-port"},
                {"server", "--tcp-port", "abc"},
                {"server", "--udp-port", "70000"},
                {"client", "--target", "127.0.0.1", "--bogus", "1"},
                {"client", "--target", "host.invalid"},
        };
        for (String[] argv : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Args.parse(argv),
                    String.join(" ", argv));
            assertTrue(e.getMessage().contains(Args.USAGE), e.getMessage());
        }
    }
}
