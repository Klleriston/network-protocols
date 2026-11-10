package dev.klleriston.netlab.wire;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Enquadramento TCP: prefixo de 4 bytes (big-endian) com o tamanho, seguido do corpo.
 *
 * <p>A escrita faz intencionalmente duas chamadas write (cabeçalho, depois corpo): é o padrão
 * write-write-read que torna o algoritmo de Nagle visível no experimento de latência.
 */
public final class TcpFraming {

    private TcpFraming() {}

    public static void write(OutputStream out, byte[] frame) throws IOException {
        byte[] header = ByteBuffer.allocate(4).putInt(frame.length).array();
        out.write(header, 0, header.length);
        out.write(frame, 0, frame.length);
        out.flush();
    }

    /** Lê um frame completo; lança EOFException em fim limpo do stream. */
    public static byte[] read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > TestMessage.MAX_BYTES) {
            throw new MessageTooLargeException(
                    "Tamanho de frame inválido: " + length + " (máximo " + TestMessage.MAX_BYTES + ")");
        }
        byte[] frame = new byte[length];
        in.readFully(frame);
        return frame;
    }
}
