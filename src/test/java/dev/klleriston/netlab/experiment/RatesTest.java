package dev.klleriston.netlab.experiment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RatesTest {

    @Test
    void parsesSuffixes() {
        assertEquals(20_000_000L, Rates.parseBitsPerSecond("20mbps"));
        assertEquals(500_000L, Rates.parseBitsPerSecond("500kbps"));
        assertEquals(1_000_000_000L, Rates.parseBitsPerSecond("1gbps"));
        assertEquals(9600L, Rates.parseBitsPerSecond("9600"));
    }

    @Test
    void isCaseInsensitiveAndAcceptsDecimals() {
        assertEquals(1_500_000L, Rates.parseBitsPerSecond("1.5Mbps"));
        assertEquals(20_000_000L, Rates.parseBitsPerSecond(" 20MBPS "));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[] {"rápido", "", "mbps", "-5mbps", "0", "0kbps", "1xbps"}) {
            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, () -> Rates.parseBitsPerSecond(bad), bad);
            assertEquals("taxa inválida: " + bad, e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Rates.parseBitsPerSecond(null));
    }
}
