package dev.klleriston.netlab.cli;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;

/** Endereço IPv4 da máquina na rede local, para montar a URL do painel que o celular abre. */
public final class LanAddress {

    private LanAddress() {}

    /**
     * Primeiro IPv4 site-local de uma interface ativa, não loopback, não virtual e não ponto a ponto;
     * na falta dele, qualquer IPv4 não loopback e não link-local dessas interfaces.
     */
    public static Optional<Inet4Address> find() {
        List<Inet4Address> candidates;
        try {
            candidates = NetworkInterface.networkInterfaces()
                    .filter(LanAddress::usable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                    .map(a -> (Inet4Address) a)
                    .toList();
        } catch (SocketException | RuntimeException e) {
            return Optional.empty();
        }
        return candidates.stream().filter(InetAddress::isSiteLocalAddress).findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    private static boolean usable(NetworkInterface ni) {
        try {
            return ni.isUp() && !ni.isLoopback() && !ni.isVirtual() && !ni.isPointToPoint();
        } catch (SocketException e) {
            return false;
        }
    }
}
