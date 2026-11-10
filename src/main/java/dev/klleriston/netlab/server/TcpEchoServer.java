package dev.klleriston.netlab.server;

import dev.klleriston.netlab.wire.MessageTooLargeException;
import dev.klleriston.netlab.wire.TcpFraming;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Servidor de echo TCP: devolve cada frame recebido, byte a byte, sem decodificar. */
public final class TcpEchoServer implements AutoCloseable {

    private final int requestedPort;
    private final Consumer<String> log;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean closed;

    public TcpEchoServer(int port, Consumer<String> log) {
        this.requestedPort = port;
        this.log = log;
    }

    /** Abre a porta e começa a aceitar conexões; lança BindException se a porta estiver em uso. */
    public void start() throws IOException {
        ServerSocket ss = new ServerSocket(requestedPort);
        serverSocket = ss;
        Thread.ofVirtual().name("tcp-echo-accept").start(() -> acceptLoop(ss));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop(ServerSocket ss) {
        while (!closed) {
            try {
                Socket s = ss.accept();
                open.add(s);
                Thread.ofVirtual().name("tcp-echo-conn").start(() -> handle(s));
            } catch (IOException e) {
                if (closed) return;
                log.accept("erro ao aceitar conexão: " + e.getMessage());
            }
        }
    }

    private void handle(Socket s) {
        String remote = String.valueOf(s.getRemoteSocketAddress());
        log.accept("conexão aceita de " + remote);
        try (s) {
            try {
                s.setTcpNoDelay(true);
            } catch (SocketException ignored) {
                // peer já abortou (macOS); o read seguinte reporta o reset
            }
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (true) {
                byte[] frame = TcpFraming.read(in);
                TcpFraming.write(s.getOutputStream(), frame);
            }
        } catch (EOFException e) {
            log.accept(remote + " fechou a conexão (FIN)");
        } catch (MessageTooLargeException e) {
            log.accept("frame muito grande de " + remote + ", fechando");
        } catch (SocketException e) {
            if (closed) return;
            String msg = e.getMessage();
            if (msg != null && isPeerAbort(msg.toLowerCase())) {
                log.accept(remote + " resetou a conexão (RST)");
            } else {
                log.accept("erro em " + remote + ": " + msg);
            }
        } catch (IOException e) {
            if (!closed) log.accept("erro em " + remote + ": " + e.getMessage());
        } finally {
            open.remove(s);
        }
    }

    /** Reset do par aparece como "reset" ou, se a escrita vier depois, como "broken pipe". */
    private static boolean isPeerAbort(String lowerMessage) {
        return lowerMessage.contains("reset") || lowerMessage.contains("broken pipe");
    }

    @Override
    public void close() {
        closed = true;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        open.clear();
    }
}
