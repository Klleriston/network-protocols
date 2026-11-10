package dev.klleriston.netlab.cli;

import dev.klleriston.netlab.server.ServerMode;
import java.io.IOException;
import java.net.BindException;

/** Ponto de entrada: {@code server} ou {@code client}. */
public final class Main {

    private Main() {}

    public static void main(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        try {
            switch (args) {
                case Args.ServerArgs s -> ServerMode.run(s.tcpPort(), s.udpPort(), System.out);
                case Args.ClientArgs c -> {
                    ClientMode client = new ClientMode(c, System.in, System.out);
                    InterruptHandler.install(() -> {
                        if (!client.interruptExperiment()) System.exit(0);
                    });
                    client.run();
                }
            }
        } catch (BindException e) {
            // ServerMode e ClientMode já imprimiram qual porta e qual flag usar
            System.exit(1);
        } catch (IOException e) {
            System.err.println("erro: " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }
}
