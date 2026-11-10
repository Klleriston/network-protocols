package dev.klleriston.netlab.experiment;

import java.net.InetAddress;

/** Servidor de echo alvo dos experimentos. */
public record Target(InetAddress host, int tcpPort, int udpPort) {

    /** {@code "host:portaTcp"}. */
    public String tcpRemote() {
        return host.getHostAddress() + ":" + tcpPort;
    }

    /** {@code "host:portaUdp"}. */
    public String udpRemote() {
        return host.getHostAddress() + ":" + udpPort;
    }
}
