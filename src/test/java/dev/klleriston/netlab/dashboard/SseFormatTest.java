package dev.klleriston.netlab.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.klleriston.netlab.event.Events;
import org.junit.jupiter.api.Test;

class SseFormatTest {

    @Test
    void formatsEventAndDataLines() {
        Events.ExperimentStarted e = new Events.ExperimentStarted(42, "latency");
        assertEquals("event: ExperimentStarted\ndata: " + e.toJson() + "\n\n", SseFormat.format(e));
    }
}
