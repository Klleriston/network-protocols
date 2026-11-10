package dev.klleriston.netlab.experiment;

import dev.klleriston.netlab.event.Event;
import dev.klleriston.netlab.event.Events;
import dev.klleriston.netlab.event.Protocol;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/** Traduz exceções de rede em eventos: erro de rede é dado, não falha. */
public final class NetErrors {

    private NetErrors() { }

    public static Optional<Event> toEvent(Exception e, Protocol p, String remote, long ts) {
        if (e instanceof PortUnreachableException) return Optional.of(new Events.UdpPortUnreachable(ts, remote));
        if (e instanceof ConnectException) return Optional.of(new Events.ConnectionRefused(ts, p, remote));
        if (e instanceof SocketTimeoutException) return Optional.of(new Events.ConnectTimeout(ts, p, remote));
        if (isPeerReset(e)) return Optional.of(new Events.ConnectionReset(ts, p, remote));
        if (e instanceof EOFException) return Optional.of(new Events.ConnectionClosed(ts, p, remote, true));
        return Optional.empty();
    }

    /** Servidor fora do ar, IP errado ou firewall bloqueando. */
    public static boolean isUnreachable(Exception e) {
        return e instanceof ConnectException
                || e instanceof SocketTimeoutException
                || e instanceof NoRouteToHostException
                || e instanceof UnknownHostException;
    }

    /** Reset do par: "reset" ou, numa escrita após o abort (macOS), "broken pipe". */
    static boolean isPeerReset(Exception e) {
        if (!(e instanceof SocketException)) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("reset") || lower.contains("broken pipe");
    }
}
