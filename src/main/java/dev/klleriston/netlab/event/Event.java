package dev.klleriston.netlab.event;

/** Evento publicado no {@link EventBus}. */
public sealed interface Event permits
        Events.ExperimentStarted, Events.ExperimentFinished, Events.ExperimentAborted,
        Events.ExperimentFailed, Events.ConnectionEstablished, Events.ConnectionClosed,
        Events.ConnectionReset, Events.ConnectionRefused, Events.ConnectTimeout,
        Events.UdpPortUnreachable, Events.PacketSent, Events.PacketEchoed, Events.PacketLost,
        Events.PacketReordered, Events.PacketLate, Events.MetricsSnapshot,
        Events.ImpairmentChanged, Events.Suppressed {

    long timestampNanos();

    Protocol protocol();

    default String type() {
        return getClass().getSimpleName();
    }

    String toJson();
}
