package dev.klleriston.netlab.cli;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Imprime no terminal os eventos de ciclo de vida, de experimento, de impair e de porta UDP inalcançável,
 * uma linha cada; ignora eventos de pacote e de métricas. Escreve sob o lock do PrintStream, que o
 * prompt também usa, para que linhas de threads diferentes não se misturem.
 */
public final class ConsolePrinter implements Consumer<Event> {

    private final PrintStream out;

    public ConsolePrinter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void accept(Event e) {
        if (!printable(e)) return;
        String line = String.format("%-22s %s", e.type(), e.toJson());
        String detail = switch (e) {
            case Events.ExperimentFinished f -> f.summary();
            case Events.ExperimentFailed f -> f.reason();
            default -> null;
        };
        synchronized (out) {
            out.println(line);
            if (detail != null) out.println(detail);
            out.flush();
        }
    }

    private static boolean printable(Event e) {
        return switch (e) {
            case Events.ExperimentStarted x -> true;
            case Events.ExperimentFinished x -> true;
            case Events.ExperimentAborted x -> true;
            case Events.ExperimentFailed x -> true;
            case Events.ConnectionEstablished x -> true;
            case Events.ConnectionClosed x -> true;
            case Events.ConnectionReset x -> true;
            case Events.ConnectionRefused x -> true;
            case Events.ConnectTimeout x -> true;
            case Events.UdpPortUnreachable x -> true;
            case Events.ImpairmentChanged x -> true;
            case Events.PacketSent x -> false;
            case Events.PacketEchoed x -> false;
            case Events.PacketLost x -> false;
            case Events.PacketReordered x -> false;
            case Events.PacketLate x -> false;
            case Events.MetricsSnapshot x -> false;
            case Events.Suppressed x -> false;
        };
    }
}
