package dev.klleriston.netlab.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventJsonTest {

    @Test
    void packetLostJsonHasTypeProtocolAndFields() {
        assertEquals("{\"type\":\"PacketLost\",\"timestampNanos\":5,\"protocol\":\"UDP\",\"seq\":9}",
                new Events.PacketLost(5, Protocol.UDP, 9).toJson());
    }

    @Test
    void experimentFailedEscapesQuotesAndNewlines() {
        String json = new Events.ExperimentFailed(1, "x", "a\"b\nc").toJson();
        assertTrue(json.contains("a\\\"b\\nc"), json);
    }

    @Test
    void eventsWithoutProtocolComponentReportNone() {
        Event e = new Events.ExperimentStarted(1, "latency");
        assertEquals(Protocol.NONE, e.protocol());
        assertEquals("{\"type\":\"ExperimentStarted\",\"timestampNanos\":1,\"protocol\":\"NONE\",\"name\":\"latency\"}",
                e.toJson());
    }

    @Test
    void udpPortUnreachableIsUdp() {
        Event e = new Events.UdpPortUnreachable(2, "127.0.0.1:7001");
        assertEquals(Protocol.UDP, e.protocol());
        assertTrue(e.toJson().contains("\"protocol\":\"UDP\""));
    }

    @Test
    void snapshotFormatsLossPercentWithTwoDecimals() {
        String json = new Events.MetricsSnapshot(1, Protocol.TCP, 10, 9, 1, 0, 0, 4.5,
                1, 2, 3, 4, 5, 6, 7).toJson();
        assertTrue(json.contains("\"lossPercent\":4.50,"), json);
        assertTrue(json.contains("\"droppedEvents\":7}"), json);
    }

    @Test
    void controlCharactersAreEscaped() {
        assertEquals("{\"k\":\"\\t\\\\\\u0001\"}", Json.object().field("k", "\t\\\u0001").build());
    }

    @Test
    void booleansAndIntsAreUnquoted() {
        assertEquals("{\"a\":true,\"b\":3}", Json.object().field("a", true).field("b", 3).build());
    }

    @Test
    void typeDefaultsToSimpleClassName() {
        assertEquals("ImpairmentChanged", new Events.ImpairmentChanged(1, 2, 3, 4, 5).type());
    }
}
