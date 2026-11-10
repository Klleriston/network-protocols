package dev.klleriston.netlab.dashboard;

import dev.klleriston.netlab.event.Event;

/** Formata eventos no protocolo Server-Sent Events. */
public final class SseFormat {

    private SseFormat() { }

    public static String format(Event e) {
        return "event: " + e.type() + "\ndata: " + e.toJson() + "\n\n";
    }
}
