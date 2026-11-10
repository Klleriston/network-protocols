package dev.klleriston.netlab.cli;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Argumentos de linha de comando: modo {@code server} ou {@code client} e suas flags. */
public sealed interface Args permits Args.ServerArgs, Args.ClientArgs {

    int DEFAULT_TCP_PORT = 7000;
    int DEFAULT_UDP_PORT = 7001;
    int DEFAULT_HTTP_PORT = 8080;

    String USAGE = """
            uso:
              java -jar netlab.jar server [--tcp-port 7000] [--udp-port 7001]
              java -jar netlab.jar client --target <ip> [--tcp-port 7000] [--udp-port 7001] [--http-port 8080]""";

    record ServerArgs(int tcpPort, int udpPort) implements Args { }

    record ClientArgs(InetAddress target, int tcpPort, int udpPort, int httpPort) implements Args { }

    /** Lê o modo e as flags; lança IllegalArgumentException com o texto de uso em caso de erro. */
    static Args parse(String[] argv) {
        if (argv.length == 0) throw usage("informe o modo: server ou client");
        boolean client = switch (argv[0]) {
            case "server" -> false;
            case "client" -> true;
            default -> throw usage("modo desconhecido: " + argv[0]);
        };
        int tcpPort = DEFAULT_TCP_PORT;
        int udpPort = DEFAULT_UDP_PORT;
        int httpPort = DEFAULT_HTTP_PORT;
        String target = null;
        for (int i = 1; i < argv.length; i += 2) {
            String flag = argv[i];
            if (i + 1 >= argv.length) throw usage("faltou o valor de " + flag);
            String value = argv[i + 1];
            switch (flag) {
                case "--tcp-port" -> tcpPort = port(flag, value);
                case "--udp-port" -> udpPort = port(flag, value);
                case "--http-port" -> {
                    if (!client) throw usage("flag desconhecida para server: " + flag);
                    httpPort = port(flag, value);
                }
                case "--target" -> {
                    if (!client) throw usage("flag desconhecida para server: " + flag);
                    target = value;
                }
                default -> throw usage("flag desconhecida: " + flag);
            }
        }
        if (!client) return new ServerArgs(tcpPort, udpPort);
        if (target == null) throw usage("o modo client exige --target <ip>");
        return new ClientArgs(resolve(target), tcpPort, udpPort, httpPort);
    }

    private static int port(String flag, String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // cai na mensagem de uso abaixo
        }
        throw usage(flag + " deve ser uma porta entre 0 e 65535: " + value);
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw usage("host desconhecido em --target: " + host);
        }
    }

    private static IllegalArgumentException usage(String problem) {
        return new IllegalArgumentException(problem + "\n" + USAGE);
    }
}
