package dev.klleriston.netlab.wire;

import java.nio.ByteBuffer;

/** Codificação big-endian: seq(8) | sentAtNanos(8) | payloadLength(4) | payload. */
public final class MessageCodec {

    private MessageCodec() {}

    public static byte[] encode(TestMessage m) {
        byte[] payload = m.payload();
        return ByteBuffer.allocate(TestMessage.HEADER_BYTES + payload.length)
                .putLong(m.seq())
                .putLong(m.sentAtNanos())
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    public static TestMessage decode(byte[] buf, int offset, int length) throws MalformedMessageException {
        if (length < TestMessage.HEADER_BYTES) {
            throw new MalformedMessageException(
                    "Mensagem menor que o cabeçalho: " + length + " < " + TestMessage.HEADER_BYTES);
        }
        ByteBuffer bb = ByteBuffer.wrap(buf, offset, length);
        long seq = bb.getLong();
        long sentAt = bb.getLong();
        int payloadLength = bb.getInt();
        if (payloadLength != length - TestMessage.HEADER_BYTES) {
            throw new MalformedMessageException("Tamanho do payload inconsistente: cabeçalho diz "
                    + payloadLength + ", disponível " + (length - TestMessage.HEADER_BYTES));
        }
        byte[] payload = new byte[payloadLength];
        bb.get(payload);
        return new TestMessage(seq, sentAt, payload);
    }
}
