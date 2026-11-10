package dev.klleriston.netlab.server;

import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;

/** Modo servidor: sobe os ecos TCP e UDP e bloqueia até a thread ser interrompida. */
public final class ServerMode {

    private ServerMode() {}

    public static void run(int tcpPort, int udpPort, PrintStream out) throws IOException {
        TcpEchoServer tcp = new TcpEchoServer(tcpPort, out::println);
        UdpEchoServer udp = new UdpEchoServer(udpPort, out::println);
        int failedPort = tcpPort;
        try {
            tcp.start();
            failedPort = udpPort;
            udp.start();
            out.println("Servidor de echo TCP na porta " + tcp.port() + ", UDP na porta " + udp.port());
            out.println("Aviso: libere o Java no Firewall do Windows para TCP " + tcp.port()
                    + " e UDP " + udp.port() + ".");
            Thread.currentThread().join();
        } catch (BindException e) {
            out.println("Porta " + failedPort + " em uso. Use --tcp-port/--udp-port para escolher outra.");
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tcp.close();
            udp.close();
        }
    }
}
