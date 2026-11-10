package dev.klleriston.netlab.wire;

/** Mensagem de teste trocada entre cliente e servidor. */
public record TestMessage(long seq, long sentAtNanos, byte[] payload) {

    public static final int HEADER_BYTES = 20;
    public static final int MAX_BYTES = 65536;

    /** Cria uma mensagem com payload zerado do tamanho indicado. */
    public static TestMessage of(long seq, long sentAtNanos, int payloadSize) {
        return new TestMessage(seq, sentAtNanos, new byte[payloadSize]);
    }
}
